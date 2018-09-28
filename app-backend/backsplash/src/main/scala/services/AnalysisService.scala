package com.azavea.rf.backsplash.analysis

import com.azavea.rf.backsplash._
import com.azavea.rf.authentication.Authentication
import com.azavea.rf.backsplash.parameters.PathParameters._
import com.azavea.rf.backsplash.nodes.ProjectNode
import com.azavea.rf.common.RollbarNotifier
import com.azavea.rf.database.{ProjectDao, SceneToProjectDao, ToolRunDao, UserDao}
import com.azavea.rf.database.util.RFTransactor
import com.azavea.rf.datamodel._
import com.azavea.maml.error.Interpreted
import com.azavea.maml.eval.BufferingInterpreter
import cats._
import cats.data._
import cats.data.Validated._
import com.azavea.maml.eval._
import cats.effect.{IO, Timer}
import org.http4s.MediaType

import cats.implicits._
import cats.syntax._
import doobie.implicits._
import geotrellis.proj4.WebMercator
import geotrellis.raster.{IntArrayTile, MultibandTile, Raster, Tile}
import geotrellis.raster._
import geotrellis.raster.render._
import geotrellis.raster.render.png._
import geotrellis.server.core.cog.CogUtils
import geotrellis.server.core.maml._
import geotrellis.server.core.maml.reification.MamlTmsReification
import geotrellis.vector.{Polygon, Projected}

import org.http4s._
import org.http4s.circe._
import org.http4s.dsl._
import org.http4s.dsl.io._
import org.http4s.implicits._
import org.http4s.server._
import org.http4s.headers._
import com.azavea.rf.tool._

import java.net.URI
import java.util.UUID
import java.security.InvalidParameterException

import doobie._
import doobie.implicits._
import doobie.Fragments.in
import doobie.postgres._
import doobie.postgres.implicits._
import com.azavea.rf.database.filter.Filterables._
import com.azavea.rf.tool.ast._
import cats.data._
import cats.data.Validated._
import cats.implicits._
import cats.free._
import com.azavea.rf.backsplash.maml.BacksplashMamlAdapter

import scala.util._
import com.azavea.rf.tool.ast.MapAlgebraAST

class AnalysisService(
                       interpreter: BufferingInterpreter = BufferingInterpreter.DEFAULT
                     )(implicit t: Timer[IO])
  extends Http4sDsl[IO]
  with RollbarNotifier
  with Authentication {

  implicit val xa = RFTransactor.xa

  // TODO: DRY OUT
  object TokenQueryParamMatcher
    extends QueryParamDecoderMatcher[String]("token")

  object NodeQueryParamMatcher
    extends OptionalQueryParamDecoderMatcher[String]("node")

  object VoidCacheQueryParamMatcher
    extends QueryParamDecoderMatcher[Boolean]("voidCache")

  implicit class MapAlgebraAstConversion(val rfmlAst: MapAlgebraAST)
    extends BacksplashMamlAdapter

  val service: HttpService[IO] =
    HttpService {
      case GET -> Root / UUIDWrapper(analysisId) / histogram
        :? TokenQueryParamMatcher(token)
        :? NodeQueryParamMatcher(node)
        :? VoidCacheQueryParamMatcher(void) => {

        ???
      }


      case GET -> Root / UUIDWrapper(analysisId) / IntVar(z) / IntVar(x) / IntVar(y)
        :? NodeQueryParamMatcher(node) => {

        logger.info(s"Requesting Analysis: ${analysisId}")
        val tr = ToolRunDao.query.filter(analysisId).select.transact(xa)

        val mapAlgebraAST = tr.flatMap{ toolRun =>
          logger.info(s"Getting AST")
          val astOption = toolRun.executionParameters.as[MapAlgebraAST].right.toOption
          (astOption, node) match {
            case (Some(ast), Some(id)) => IO.pure(ast.find(UUID.fromString(id)).getOrElse(
              throw new InvalidParameterException(s"Node ${id} missing from in AST ${analysisId}")))
            case (Some(ast), _) => IO.pure(ast)
          }
        }

        logger.debug(s"AST: ${mapAlgebraAST}")
        mapAlgebraAST.flatMap { ast =>
          val (exp, mdOption, params) = ast.asMaml
          val mamlEval = MamlTms.apply(IO.pure(exp), IO.pure(params), interpreter)
          val tileIO = mamlEval(z, x, y)
          tileIO.attempt flatMap {
            case Left(error) => ???
            case Right(Valid(tile)) => {
              val colorMap  = for {
                md <- mdOption
                renderDef <- md.renderDef
              } yield renderDef

              colorMap match {
                case Some(rd) => {
                  logger.debug(s"Using Render Definition: ${rd}")
                  Ok(tile.renderPng(rd).bytes, `Content-Type`(MediaType.`image/png`))
                }
                case _ => {
                  logger.debug(s"Using Default Color Ramp: Viridis")
                  Ok(tile.renderPng(ColorRamps.Viridis).bytes, `Content-Type`(MediaType.`image/png`))
                }
              }
            }
            case Right(Invalid(e)) => {
              BadRequest(e.toString)
            }
          }
        }
      }
    }
}
