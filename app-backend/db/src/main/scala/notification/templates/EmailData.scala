package com.rasterfoundry.database.notification.templates

case class EmailData(
  subject: String,
  plainBody: String,
  richBody: String
)
