package com.azavea.rf.database

import simulacrum._

import com.azavea.rf.datamodel._
import com.azavea.rf.database.Implicits._
import doobie._
import doobie.implicits._
import doobie.postgres._
import doobie.postgres.implicits._
import cats._
import cats.data._
import cats.effect.IO
import cats.implicits._
import java.util.UUID

@typeclass trait ObjectPermissions[A] {
  def tableName: String

  def getPermissions(id: UUID): ConnectionIO[List[Option[ObjectAccessControlRule]]] =
    (Fragment.const(s"SELECT acrs FROM ${tableName}") ++ Fragments.whereAndOpt(Some(fr"id = ${id}")))
    .query[List[String]]
    .unique
    .map(acrStringsList => acrStringsList.map(ObjectAccessControlRule.fromObjAcrString(_)))
}
