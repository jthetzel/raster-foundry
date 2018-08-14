package com.azavea.rf.datamodel

import io.circe._
import java.util.UUID
import java.sql.Timestamp

import io.circe.generic.JsonCodec

@JsonCodec
case class Datasource(
  id: UUID,
  createdAt: java.sql.Timestamp,
  createdBy: String,
  modifiedAt: java.sql.Timestamp,
  modifiedBy: String,
  owner: String,
  name: String,
  visibility: Visibility,
  composites: Json,
  extras: Json,
  bands: Json,
  licenseName: Option[String],
  acrs: Option[List[Option[String]]]
) {
  def toThin: Datasource.Thin = Datasource.Thin(this.bands, this.name, this.id)
}

object Datasource {

  def tupled = (Datasource.apply _).tupled

  def create = Create.apply _

  @JsonCodec
  case class Thin (
    bands: Json,
    name: String,
    id: UUID
  )

  @JsonCodec
  case class Create (
    name: String,
    visibility: Visibility,
    owner: Option[String],
    composites: Json,
    extras: Json,
    bands: Json,
    licenseName: Option[String]
  ) extends OwnerCheck  {
    def toDatasource(user: User): Datasource = {
      val id = java.util.UUID.randomUUID()
      val now = new Timestamp((new java.util.Date()).getTime())

      val ownerId = checkOwner(user, this.owner)

      Datasource(
        id,
        now, // createdAt
        user.id, // createdBy
        now, // modifiedAt
        user.id, // modifiedBy
        ownerId, // owner
        this.name,
        this.visibility,
        this.composites,
        this.extras,
        this.bands,
        this.licenseName,
        None
      )
    }
  }
}

@JsonCodec
case class ObjectAccessControlRule (
  acrs: Option[List[Option[String]]]
)

object ObjectAccessControlRule {
  def create = Create.apply _

  @JsonCodec
  case class Create(
    subjectType: SubjectType,
    subjectIdO: Option[String],
    actionType: ActionType
  ) {
    val subjectId: String = subjectIdO match {
      case Some(subjectId) => subjectId
      case _ => ""
    }

    def toAccessControlRule: ObjectAccessControlRule = ObjectAccessControlRule(
      Some(
        List(Some(s"${subjectType.toString};${subjectId};${actionType.toString}"))
      )
    )

    def toAccessControlRuleString: Option[String] =
      Some(s"${subjectType.toString};${subjectId};${actionType.toString}")
  }
}
