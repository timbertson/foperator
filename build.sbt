scalaVersion in ThisBuild := "2.13.2"

lazy val root = (project in file(".")).settings(
  name := "foperator",
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
