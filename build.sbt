import ScalaProject._

val fs2Version = "2.5.10"
val monix = "io.monix" %% "monix" % "3.2.2"
val logback = "ch.qos.logback" % "logback-classic" % "1.2.3"
val scalatest = "org.scalatest" %% "scalatest" % "3.1.0" % "test"

val common = Seq(
  version := "1.0",

  organization := "net.gfxmonk",
  scalacOptions ~= (_ filterNot (_ == "-Xfatal-warnings")),

  libraryDependencies ++= Seq(
    "org.typelevel" %% "cats-core" % "2.1.0",
    "org.typelevel" %% "cats-effect" % "2.5.3",
    "co.fs2" %% "fs2-core" % fs2Version,
    "org.slf4j" % "slf4j-api" % "1.7.9",
  )
)

lazy val core = (project in file("."))
  .settings(common)
  .settings(
    name := "foperator-core",
    libraryDependencies ++= Seq(
      "net.gfxmonk" %% "auditspec" % "0.1.0",
    )
  )

// split out of core so that it doesn't depend on monix
lazy val testkit = (project in file("testkit"))
  .settings(common)
  .settings(
    name := "foperator-testkit",
    libraryDependencies ++= Seq(
      monix,
    )
  ).dependsOn(core)

// a separate project so it can depend on testkit
lazy val tests = (project in file("tests"))
  .settings(common)
  .settings(hiddenProjectSettings)
  .settings(
    name := "foperator-tests",
    libraryDependencies ++= Seq(
      monix,
      logback,
      scalatest,
    )
  ).dependsOn(core, testkit)

// skuber backend
lazy val skuber = (project in file("skuber"))
  .settings(common)
  .settings(
    name := "foperator-skuber",
    libraryDependencies ++= Seq(
      "io.skuber" %% "skuber" % "2.4.0",
      "co.fs2" %% "fs2-reactive-streams" % fs2Version,
      "com.typesafe.akka" %% "akka-slf4j" % "2.5.29",
    )
  ).dependsOn(core)

lazy val sample = (project in file("sample"))
  .settings(common)
  .settings(hiddenProjectSettings)
  .settings(
    name := "foperator-sample",
    libraryDependencies ++= Seq(logback, scalatest),
    packMain := Map(
      "simple" -> "net.gfxmonk.foperator.sample.SimpleMain",
      "advanced" -> "net.gfxmonk.foperator.sample.AdvancedMain",
      "simple-mutator" -> "net.gfxmonk.foperator.sample.mutator.Simple",
      "advanced-mutator" -> "net.gfxmonk.foperator.sample.mutator.Advanced",
      "mutator" -> "net.gfxmonk.foperator.sample.mutator.Standalone",
      "mutator-test" -> "net.gfxmonk.foperator.sample.mutator.MutatorTest",
      "mutator-test-live" -> "net.gfxmonk.foperator.sample.MutatorTestLive",
    ),
  ).dependsOn(core, testkit, skuber).enablePlugins(PackPlugin)
