import ScalaProject._

val fs2Version = "2.5.10"
val monixVersion = "3.2.2"
val monixEval = "io.monix" %% "monix-eval" % monixVersion
val monixReactive = "io.monix" %% "monix-reactive" % monixVersion
val logback = "ch.qos.logback" % "logback-classic" % "1.2.3"

val weaverVersion = "0.6.9"
val weaverSettings = Seq(
  libraryDependencies ++= Seq(
    "com.disneystreaming" %% "weaver-cats" % weaverVersion % Test,
    "com.disneystreaming" %% "weaver-monix" % weaverVersion % Test,
  ),
  testFrameworks ++= Seq(
    new TestFramework("weaver.framework.CatsEffect"),
    new TestFramework("weaver.framework.Monix"),
  )
)

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

lazy val core = (project in file("core"))
  .settings(common)
  .settings(
    name := "foperator-core",
    libraryDependencies ++= Seq(
    )
  )

// split out of core so that it doesn't depend on monix
lazy val testkit = (project in file("testkit"))
  .settings(common)
  .settings(
    name := "foperator-testkit",
    libraryDependencies ++= Seq(
      monixEval,
    )
  ).dependsOn(core)

// a separate project so it can depend on testkit
lazy val tests = (project in file("tests"))
  .settings(common)
  .settings(hiddenProjectSettings)
  .settings(
    name := "foperator-tests",
    libraryDependencies ++= Seq(
      monixEval,
      logback,
      "net.gfxmonk" %% "auditspec" % "0.1.0" % "test",
    )
  )
  .settings(weaverSettings)
  .dependsOn(core, testkit)

// skuber backend
lazy val skuber = (project in file("backends/skuber"))
  .settings(common)
  .settings(
    name := "foperator-backend-skuber",
    libraryDependencies ++= Seq(
      "io.skuber" %% "skuber" % "2.6.2",
      "co.fs2" %% "fs2-reactive-streams" % fs2Version,
      monixReactive,
      "com.typesafe.akka" %% "akka-slf4j" % "2.6.15",
    )
  ).dependsOn(core)

// kubernetes-client backend
lazy val kclient = (project in file("backends/kubernetes-client"))
  .settings(common)
  .settings(
    name := "foperator-backend-kubernetes-client",
    libraryDependencies ++= Seq(
      // v0.7.0 requires cats 3.x
      "com.goyeau" %% "kubernetes-client" % "0.6.0",
      monixEval
    )
  ).dependsOn(core)

lazy val sample = (project in file("sample"))
  .settings(common)
  .settings(hiddenProjectSettings)
  .settings(weaverSettings)
  .settings(
    name := "foperator-sample",
    libraryDependencies ++= Seq(logback),
    packMain := Map(
      // sample operators
      "simple" -> "foperator.sample.SimpleOperator",
      "advanced" -> "foperator.sample.AdvancedOperator",

      // generic
      "skuber" -> "foperator.sample.generic.SkuberMain",
      "kubernetes-client" -> "foperator.sample.generic.KubernetesClientMain",

      // mutator
      "simple-mutator" -> "foperator.sample.mutator.Simple",
      "advanced-mutator" -> "foperator.sample.mutator.Advanced",
      "mutator" -> "foperator.sample.mutator.Standalone",
      "mutator-test" -> "foperator.sample.mutator.MutatorTest",
      "mutator-test-live" -> "foperator.sample.MutatorTestLive",
    ),
  ).dependsOn(core, testkit, skuber, kclient).enablePlugins(PackPlugin)

lazy val all = (project in file(".")).settings(hiddenProjectSettings).aggregate(testkit, tests, skuber, kclient, sample)