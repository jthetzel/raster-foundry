package com.rasterfoundry.datamodel

import slick.dbio.DBIO

case class ListQueryResult[T](
  records: DBIO[Seq[T]],
  nRecords: DBIO[Int]
)
