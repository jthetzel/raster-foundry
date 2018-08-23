package com.rasterfoundry.batch.ingest.json

import java.util.UUID

import com.rasterfoundry.datamodel._
import io.circe.generic.JsonCodec

@JsonCodec
case class S3IngestStatus(sceneId: UUID, ingestStatus: IngestStatus)

