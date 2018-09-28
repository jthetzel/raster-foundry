package com.azavea.rf.backsplash.nodes

import java.util.UUID

import cats.data.{OptionT, NonEmptyList => NEL}
import cats.effect.{IO, Timer}
import cats.implicits._
import com.azavea.maml.ast.{Literal, MamlKind, RasterLit}
import com.azavea.rf.common.RollbarNotifier
import com.azavea.rf.database._
import com.azavea.rf.datamodel.{ColorRampMosaic, MosaicDefinition, SceneType, SingleBandOptions}
import doobie.implicits._
import geotrellis.proj4.{CRS, Proj4Transform, WebMercator}
import geotrellis.raster.histogram._
import geotrellis.raster.io.geotiff.{Auto, AutoHigherResolution}
import geotrellis.raster.io.json.HistogramJsonFormats
import geotrellis.raster.reproject.ReprojectRasterExtent
import geotrellis.raster.{CellSize, Raster, io => _, _}
import geotrellis.server.core.cog.CogUtils
import geotrellis.server.core.cog.CogUtils.{closestTiffOverview, cropGeoTiff, tmsLevels}
import geotrellis.server.core.maml.metadata._
import geotrellis.server.core.maml.reification._
import geotrellis.spark.io._
import geotrellis.spark.io.postgres.PostgresAttributeStore
import geotrellis.spark.io.s3.S3ValueReader
import geotrellis.spark.tiling.{LayoutDefinition, ZoomedLayoutScheme}
import geotrellis.spark.{io => _, _}
import geotrellis.vector.{Extent, Projected}
import io.circe.generic.semiauto._
import spray.json.DefaultJsonProtocol._
import spray.json._

import scala.concurrent.ExecutionContext.Implicits.global

case class ProjectNode(
                        projectId: UUID,
                        redBandOverride: Option[Int] = None,
                        greenBandOverride: Option[Int] = None,
                        blueBandOverride: Option[Int] = None,
                        isSingleBand: Boolean = false,
                        singleBandOptions: Option[SingleBandOptions.Params] = None
                      ) {
  def getBandOverrides: Option[(Int, Int, Int)] =
    (redBandOverride, greenBandOverride, blueBandOverride).tupled
}

object ProjectNode extends RollbarNotifier with HistogramJsonFormats {

  // imported here so import ...backsplash.nodes._ doesn't import a transactor
  import com.azavea.rf.database.util.RFTransactor.xa

  val store = PostgresAttributeStore()

  implicit val projectNodeDecoder = deriveDecoder[ProjectNode]
  implicit val projectNodeEncoder = deriveEncoder[ProjectNode]

  def getClosest(cellWidth: Double, listNums: List[LayoutDefinition]) = listNums match {
    case Nil => Double.MaxValue
    case list => list.minBy(ld => math.abs(ld.cellSize.width - cellWidth))
  }

  implicit val projectNodeExtentReification: MamlExtentReification[ProjectNode] = {

    def kind(self: ProjectNode): MamlKind = MamlKind.Tile

    def extentReification(self: ProjectNode)(implicit t: Timer[IO]): (Extent, CellSize) => IO[Literal] =
      (extent: Extent, cs: CellSize) => {
        val mosaicDefinitons = getMosaicDefinitions(self, extent)


        val scheme = ZoomedLayoutScheme(WebMercator, 256)
        val zoom = scheme.levelFor(extent, cs).zoom

        ???
      }

    ???
  }


  implicit val projectNodeDefinitionRasterExtents: HasRasterExtents[ProjectNode] = new HasRasterExtents[ProjectNode] {
    def rasterExtents(self: ProjectNode)(implicit t: Timer[IO]): IO[NEL[RasterExtent]] = {
      ???
    }

    def crs(self: ProjectNode)(implicit t: Timer[IO]): IO[CRS] = {
      ???
    }
  }

  implicit val projectNodeTmsReification: MamlTmsReification[ProjectNode] =
    new MamlTmsReification[ProjectNode] {
      def kind(self: ProjectNode): MamlKind = MamlKind.Tile

      def tmsReification(self: ProjectNode, buffer: Int)(
        implicit t: Timer[IO]): (Int, Int, Int) => IO[Literal] =
        (z: Int, x: Int, y: Int) => {
          val extent = CogUtils.tmsLevels(z).mapTransform.keyToExtent(x, y)
          val mdIO = getMosaicDefinitions(self, extent)
          for {
            mds <- mdIO
            mbTiles <- getMosaicDefinitionTiles(self, z, x, y, extent, mds)
          } yield {
            RasterLit(
              mbTiles.flatten match {
                case Nil => {
                  logger.info(s"NO DATA")
                  Raster(IntArrayTile.fill(NODATA, 256, 256), extent)
                }
                case tiles@(h :: _) => tiles reduce {
                  _ merge _
                }
              }
            )
          }
        }
    }


  def getMosaicDefinitionTiles(self: ProjectNode, z: Int, x: Int, y: Int, extent: Extent, mds: Seq[MosaicDefinition]) = {
    mds.toList.parTraverse(self.isSingleBand match {
      case false =>
        getMultiBandTileFromMosaic(z, x, y, extent)
      case true => {
        logger.info(s"Getting Single Band Tile From Mosaic: ${z} ${x} ${y} ${self.projectId}")
        getSingleBandTileFromMosaic(
          z,
          x,
          y,
          extent,
          self.singleBandOptions getOrElse {
            throw new Exception(
              "No single-band options found for single-band visualization")
          })
      }
    })
  }

  def getSingleBandTileFromMosaic(z: Int, x: Int, y: Int, extent: Extent, singleBandOptions: SingleBandOptions.Params)(
    md: MosaicDefinition)(implicit t: Timer[IO]): IO[Option[Raster[Tile]]] =
    md.sceneType match {
      case Some(SceneType.COG) =>
        fetchSingleBandCogTile(md, z, x, y, extent, singleBandOptions).value
      case Some(SceneType.Avro) =>
        fetchSingleBandAvroTile(md, z, x, y, extent, singleBandOptions).value
      case None =>
        throw new Exception("Unable to fetch tiles with unknown scene type")
    }

  def fetchSingleBandAvroTile(md: MosaicDefinition,
                              zoom: Int,
                              col: Int,
                              row: Int,
                              extent: Extent,
                              singleBandOptions: SingleBandOptions.Params)(
                               implicit t: Timer[IO]): OptionT[IO, Raster[Tile]] = {
    OptionT(
      for {
        _ <- IO.pure(
          logger.debug(
            s"Fetching single-band avro tile for scene id ${md.sceneId}"))
        metadata <- IO.shift(t) *> tileLayerMetadata(md.sceneId, zoom)
        (sourceZoom, tlm) = metadata
        zoomDiff = zoom - sourceZoom
        resolutionDiff = 1 << zoomDiff
        sourceKey = SpatialKey(col / resolutionDiff, row / resolutionDiff)
        histograms <- IO.shift(t) *> layerHistogram(md.sceneId)
        mbTileE <- {
          if (tlm.bounds.includes(sourceKey))
            avroLayerTile(md.sceneId, sourceZoom, sourceKey).attempt
          else
            IO(
              Left(
                new Exception(
                  s"Source key outside of tile layer bounds for scene ${md.sceneId}, key ${sourceKey}")
              )
            )
        }
      } yield {
        (mbTileE map {
          (mbTile: MultibandTile) => {
            val tile = mbTile.bands.lift(singleBandOptions.band) getOrElse {
              throw new Exception("No band found in single-band options")
            }
            val histogram = histograms
              .lift(singleBandOptions.band) getOrElse {
              throw new Exception("No histogram found for band")
            }
            Raster(tile, extent)
            //            colorSingleBandTile(tile, extent, histogram, singleBandOptions)
          }
        }).toOption
      }
    )
  }

  def tileLayerMetadata(id: UUID, zoom: Int): IO[(Int, TileLayerMetadata[SpatialKey])] = {
    val layerName = id.toString
    LayerAttributeDao.unsafeMaxZoomForLayer(layerName).transact(xa) map {
      case (_, maxZoom) =>
        val z = if (zoom > maxZoom) maxZoom else zoom
        z -> store.readMetadata[TileLayerMetadata[SpatialKey]](
          LayerId(layerName, z))
    }
  }

  def layerHistogram(id: UUID): IO[Array[Histogram[Double]]] = {
    val layerId = LayerId(name = id.toString, zoom = 0)
    LayerAttributeDao
      .unsafeGetAttribute(layerId, "histogram")
      .transact(xa) map { attribute =>
      attribute.value.noSpaces.parseJson.convertTo[Array[Histogram[Double]]]
    }
  }

  def avroLayerTile(id: UUID, zoom: Int, key: SpatialKey): IO[MultibandTile] = {
    val reader = new S3ValueReader(store)
      .reader[SpatialKey, MultibandTile](LayerId(id.toString, zoom))
    IO(reader.read(key))
  }

  def fetchSingleBandCogTile(md: MosaicDefinition,
                             zoom: Int,
                             col: Int,
                             row: Int,
                             extent: Extent,
                             singleBandOptions: SingleBandOptions.Params)(
                              implicit t: Timer[IO]): OptionT[IO, Raster[Tile]] = {
    val tileIO = for {
      _ <- IO.pure(
        logger.debug(
          s"Fetching single-band COG tile for scene ID ${md.sceneId}"))
      raster <- IO.shift(t) *> CogUtils.fetch(
        md.ingestLocation.getOrElse(
          "Cannot fetch scene with no ingest location"
        ),
        zoom,
        col,
        row)
      histograms <- IO.shift(t) *> layerHistogram(md.sceneId)
    } yield {
      logger.debug(s"Retrieved Tile: ${raster.tile.dimensions}")
      val tile = raster.tile.bands.lift(singleBandOptions.band) getOrElse {
        throw new Exception("No band found in single-band options")
      }
      val histogram = histograms
        .lift(singleBandOptions.band) getOrElse {
        throw new Exception("No histogram found for band")
      }
      Raster(tile, extent)
      //      colorSingleBandTile(tile, extent, histogram, singleBandOptions)
    }
    OptionT(tileIO.attempt.map(_.toOption))
  }

  def getMultiBandTileFromMosaic(z: Int, x: Int, y: Int, extent: Extent)(
    md: MosaicDefinition)(implicit t: Timer[IO]): IO[Option[Raster[Tile]]] =
    md.sceneType match {
      case Some(SceneType.COG) =>
        fetchMultiBandCogTile(md, z, x, y, extent).value
      case Some(SceneType.Avro) =>
        fetchMultiBandAvroTile(md, z, x, y, extent).value
      case None =>
        throw new Exception("Unable to fetch tiles with unknown scene type")
    }

  // TODO: this essentially inlines a bunch of logic from LayerCache, which isn't super cool
  // it would be nice to get that logic somewhere more appropriate, especially since a lot of
  // it is grid <-> geometry math, but I'm not certain where it should go.
  def fetchMultiBandAvroTile(
                              md: MosaicDefinition,
                              zoom: Int,
                              col: Int,
                              row: Int,
                              extent: Extent)(implicit t: Timer[IO]): OptionT[IO, Raster[Tile]] = {
    OptionT(
      for {
        _ <- IO.pure(
          logger.debug(
            s"Fetching multi-band avro tile for scene id ${md.sceneId}"))
        metadata <- IO.shift(t) *> tileLayerMetadata(md.sceneId, zoom)
        (sourceZoom, tlm) = metadata
        zoomDiff = zoom - sourceZoom
        resolutionDiff = 1 << zoomDiff
        sourceKey = SpatialKey(col / resolutionDiff, row / resolutionDiff)
        histograms <- IO.shift(t) *> layerHistogram(md.sceneId)
        mbTileE <- {
          if (tlm.bounds.includes(sourceKey))
            avroLayerTile(md.sceneId, sourceZoom, sourceKey).attempt
          else
            IO(
              Left(
                new Exception(
                  s"Source key outside of tile layer bounds for scene ${md.sceneId}, key ${sourceKey}")
              )
            )
        }
      } yield {
        (mbTileE map {
          (mbTile: MultibandTile) => {
            val innerCol = col % resolutionDiff
            val innerRow = row % resolutionDiff
            val cols = mbTile.cols / resolutionDiff
            val rows = mbTile.rows / resolutionDiff
            val corrected =
              md.colorCorrections.colorCorrect(mbTile, histograms.toSeq)
            Raster(corrected.color, extent).resample(256, 256)
          }
        }).toOption
      }
    )
  }

  def fetchMultiBandCogTile(
                             md: MosaicDefinition,
                             zoom: Int,
                             col: Int,
                             row: Int,
                             extent: Extent)(implicit t: Timer[IO]): OptionT[IO, Raster[Tile]] = {
    val tileIO = for {
      _ <- IO.pure(logger.debug(s"Fetching multi-band COG tile for scene ID ${md.sceneId}"))
      raster <- IO.shift(t) *> CogUtils.fetch(md.ingestLocation.getOrElse("Cannot fetch scene with no ingest location"),
        zoom,
        col,
        row)
      histograms <- IO.shift(t) *> layerHistogram(md.sceneId)
    } yield {
      val bandOrder = List(
        md.colorCorrections.redBand,
        md.colorCorrections.greenBand,
        md.colorCorrections.blueBand
      )
      val subsetBands = raster.tile.subsetBands(bandOrder)
      val subsetHistograms = bandOrder map histograms
      val normalized = (
        subsetBands.mapBands { (i: Int, tile: Tile) => {
          (subsetHistograms(i).minValue, subsetHistograms(i).maxValue) match {
            case (Some(min), Some(max)) => tile.normalize(min, max, 0, 255)
            case _ =>
              throw new Exception(
                "Histogram bands don't match up with tile bands")
          }
        }
        }
        ).color

      Raster(normalized, extent).resample(256, 256)
    }
    OptionT(tileIO.attempt.map(_.toOption))
  }

  def getMosaicDefinitions(self: ProjectNode, extent: Extent): IO[Seq[MosaicDefinition]] = {
    self.getBandOverrides match {
      case Some((red, green, blue)) =>
        SceneToProjectDao
          .getMosaicDefinition(
            self.projectId,
            Some(Projected(extent, 3857)),
            Some(red),
            Some(green),
            Some(blue)
          )
          .transact(xa)
      case None =>
        SceneToProjectDao
          .getMosaicDefinition(
            self.projectId,
            Some(Projected(extent, 3857))
          )
          .transact(xa)
    }
  }

  def colorSingleBandTile(
                           tile: Tile,
                           extent: Extent,
                           histogram: Histogram[Double],
                           singleBandOptions: SingleBandOptions.Params): Raster[Tile] = {
    logger.debug(s"Applying Colorings")
    val colorScheme = singleBandOptions.colorScheme
    val colorMap = (colorScheme.asArray,
      colorScheme.asObject,
      singleBandOptions.extraNoData) match {
      case (Some(a), None, _) =>
        ColorRampMosaic.colorMapFromVector(a.map(_.noSpaces),
          singleBandOptions,
          histogram)
      case (None, Some(o), Nil) =>
        ColorRampMosaic.colorMapFromMap(o.toMap map {
          case (k, v) => (k, v.noSpaces)
        })
      case (None, Some(o), masked@(h +: t)) =>
        ColorRampMosaic.colorMapFromMap(o.toMap map {
          case (k, v) =>
            (k, if (masked.contains(k.toInt)) "#00000000" else v.noSpaces)
        })
      case _ => {
        val message =
          "Invalid color scheme format. Color schemes must be defined as an array of hex colors or a mapping of raster values to hex colors."
        logger.error(message)
        throw new IllegalArgumentException(message)
      }
    }
    Raster(tile.color(colorMap), extent)
  }

  def fetchMultiBandCogTile(md: MosaicDefinition, zoom: Int, extent: Extent)
                           (implicit t: Timer[IO]): OptionT[IO, Raster[Tile]] = {
    val uri = md.ingestLocation.getOrElse(throw new IllegalArgumentException("Missing Ingest Location"))
    CogUtils.fromUri(uri).flatMap { tiff =>
      val transform = Proj4Transform(tiff.crs, WebMercator)
      val inverseTransform = Proj4Transform(WebMercator, tiff.crs)
      val tmsTileRE = RasterExtent(
        extent = extent,
        cols = 256, rows = 256
      )
      val tiffTileRE = ReprojectRasterExtent(tmsTileRE, inverseTransform)
      val overview = closestTiffOverview(tiff, tiffTileRE.cellSize, AutoHigherResolution(0))

      cropGeoTiff(overview, tiffTileRE.extent).map { raster =>
        raster.reproject(tmsTileRE, transform, inverseTransform)
      }
    }
  }
}
