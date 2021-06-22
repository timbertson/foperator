val common = Seq(
  version := "1.0",

  organization := "net.gfxmonk",
  scalacOptions ~= (_ filterNot (_ == "-Xfatal-warnings")),

  libraryDependencies ++= Seq(
    "io.skuber" %% "skuber" % "2.4.0",
    "io.monix" %% "monix" % "3.2.2",
    "org.typelevel" %% "cats-core" % "2.1.0",
    "org.slf4j" % "slf4j-api" % "1.7.9",
    "org.scalatest" %% "scalatest" % "3.1.0" % Test,
    "com.typesafe.akka" %% "akka-slf4j" % "2.5.29",
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
      "ch.qos.logback" % "logback-classic" % "1.2.3",
    ),
    packMain := Map(
      "simple" -> "net.gfxmonk.foperator.sample.SimpleMain",
      "advanced" -> "net.gfxmonk.foperator.sample.AdvancedMain",
      "simple-mutator" -> "net.gfxmonk.foperator.sample.mutator.Simple",
      "advanced-mutator" -> "net.gfxmonk.foperator.sample.mutator.Advanced",
      "mutator" -> "net.gfxmonk.foperator.sample.mutator.Standalone",
      "mutator-test" -> "net.gfxmonk.foperator.sample.mutator.MutatorTest",
      "mutator-test-live" -> "net.gfxmonk.foperator.sample.MutatorTestLive",
    ),
  ).dependsOn(lib).enablePlugins(PackPlugin)
