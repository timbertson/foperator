scalaVersion in ThisBuild := "2.13.2"

val common = Seq(
  version := "1.0",

  organization := "net.gfxmonk",
  scalacOptions ~= (_ filterNot (_ == "-Xfatal-warnings")),

  libraryDependencies ++= Seq(
    "io.skuber" %% "skuber" % "2.4.0",
    "io.monix" %% "monix" % "3.1.0",
    "org.typelevel" %% "cats-core" % "2.1.0",
    "org.slf4j" % "slf4j-simple" % "1.6.2",
    "org.scalatest" %% "scalatest" % "3.1.0" % Test
  )
)

lazy val lib = (project in file("."))
  .settings(common)
  .settings(
    name := "foperator"
  )

lazy val sample = (project in file("sample"))
  .settings(common)
  .settings(
    name := "foperator-sample",
    libraryDependencies ++= Seq(
      "ch.qos.logback" % "logback-classic" % "1.2.3"
    )
  ).dependsOn(lib)
