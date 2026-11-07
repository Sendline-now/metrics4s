import Dependencies.Versions
import com.typesafe.tools.mima.plugin.MimaKeys.mimaPreviousArtifacts

lazy val binaryCompatibleWith = Set.empty[String]

lazy val requiredJdk = 25

lazy val checkedJdk: Int = {
  val running = sys.props.getOrElse("java.specification.version", "unknown")
  val major   = running.split('.').headOption.flatMap(_.toIntOption).getOrElse(0)

  if (major < requiredJdk) {
    sys.error(
      s"metrics4s requires JDK $requiredJdk or newer; this build is running on JDK $running. " +
        "kyo ships Java 25 class files, so metrics4s-kyo cannot be compiled by an older JDK. " +
        "Point JAVA_HOME at a JDK 25 install (`cs java --jvm 25`, or `brew install openjdk@25`) and retry."
    )
  }

  major
}

Global / onLoad := {
  val _ = checkedJdk
  (Global / onLoad).value
}

lazy val sourceLevel = settingKey[String]("Scala -source level the build compiles against")

lazy val commonSettings = Seq(
  organization           := "now.sendline",
  organizationName       := "Sendline",
  organizationHomepage   := Some(uri("https://oss.sendline.now/")),
  homepage               := Some(uri("https://oss.sendline.now/metrics4s/")),
  description            := "Typed, effect-agnostic metrics for Scala 3 on the Prometheus Java client",
  version                := "0.2.0",
  versionScheme          := Some("semver-spec"),
  scalaVersion           := Versions.scala3,
  parallelExecution      := true,
  publishMavenStyle      := true,
  Test / publishArtifact := false,
  licenses               := List(License.Apache2),
  pomIncludeRepository   := { _ => false },
  publishTo              := localStaging.value,
  scmInfo                := Some(
    ScmInfo(
      uri("https://github.com/sendline-now/metrics4s"),
      "git@github.com:sendline-now/metrics4s.git",
    )
  ),
  developers             := List(
    Developer(
      "shadowsmind",
      "Alexandr Oshlakov",
      "shadowsmind.dev@gmail.com",
      uri("https://github.com/shadowsmind"),
    )
  ),
  libraryDependencies ++= Dependencies.Testing.all,
  exportJars             := false,
  sourceLevel            := "3.9",
  scalacOptions ++= Seq(
    "-encoding",
    "UTF-8",
    "-java-output-version",
    "17",
    s"-source:${sourceLevel.value}",
    "-deprecation",
    "-feature",
    "-unchecked",
    "-Xcheck-macros",
    "-Wunused:all",
    "-Wvalue-discard",
    "-WunstableInlineAccessors",
    "-Wshadow:all",
    "-Winfer-union",
    "-Wimplausible-patterns",
    "-Wrecurse-with-default",
  ),
  scalacOptions ++= (if (sourceLevel.value == "future") Nil else Seq("-Werror", "-Wconf:msg=@nowarn annotation does not suppress any warnings:s")),
  credentials ++= Seq(Path.userHome / ".sbt" / "sonatype_credentials").filter(_.isFile).map(Credentials(_)),
  mimaPreviousArtifacts  := binaryCompatibleWith.map(organization.value %% moduleName.value % _),
)

lazy val aggregateSettings = Seq(
  publish / skip        := true,
  mimaPreviousArtifacts := Set.empty,
)

lazy val core = project
  .in(file("core"))
  .settings(commonSettings)
  .settings(
    name := "metrics4s-core"
  )

lazy val prometheus = project
  .in(file("backend/prometheus"))
  .settings(commonSettings)
  .settings(
    name := "metrics4s-prometheus",
    libraryDependencies ++= Dependencies.Prometheus.all,
  )
  .dependsOn(core)

lazy val backend = project
  .in(file("backend"))
  .settings(commonSettings)
  .settings(aggregateSettings)
  .aggregate(prometheus)

lazy val cats = project
  .in(file("runtime/cats"))
  .settings(commonSettings)
  .settings(
    name := "metrics4s-cats",
    libraryDependencies ++= Dependencies.Cats.all,
  )
  .dependsOn(core % "compile->compile;test->test", testkit % Test)

lazy val zio = project
  .in(file("runtime/zio"))
  .settings(commonSettings)
  .settings(
    name := "metrics4s-zio",
    libraryDependencies ++= Dependencies.Zio.all,
  )
  .dependsOn(core % "compile->compile;test->test", testkit % Test)

lazy val kyo = project
  .in(file("runtime/kyo"))
  .settings(commonSettings)
  .settings(
    name := "metrics4s-kyo",
    libraryDependencies ++= Dependencies.Kyo.all,
  )
  .dependsOn(core % "compile->compile;test->test", testkit % Test)

lazy val rapid = project
  .in(file("runtime/rapid"))
  .settings(commonSettings)
  .settings(
    name := "metrics4s-rapid",
    libraryDependencies ++= Dependencies.Rapid.all,
  )
  .dependsOn(core % "compile->compile;test->test", testkit % Test)

lazy val runtime = project
  .in(file("runtime"))
  .settings(commonSettings)
  .settings(aggregateSettings)
  .aggregate(cats, zio, kyo, rapid)

lazy val testkit = project
  .in(file("testkit"))
  .settings(commonSettings)
  .settings(
    name := "metrics4s-testkit"
  )
  .dependsOn(prometheus, grafana, core % "test->test")

lazy val grafana = project
  .in(file("grafana"))
  .settings(commonSettings)
  .settings(
    name := "metrics4s-grafana"
  )
  .dependsOn(core)

lazy val http = project
  .in(file("integrations/http"))
  .settings(commonSettings)
  .settings(
    name := "metrics4s-http"
  )
  .dependsOn(core, testkit % Test)

lazy val http4s = project
  .in(file("integrations/http4s"))
  .settings(commonSettings)
  .settings(
    name := "metrics4s-http4s",
    libraryDependencies ++= Dependencies.Http4s.all ++ Dependencies.Cats.all,
  )
  .dependsOn(http, cats, testkit % Test)

lazy val tapir = project
  .in(file("integrations/tapir"))
  .settings(commonSettings)
  .settings(
    name := "metrics4s-tapir",
    libraryDependencies ++= Dependencies.Tapir.all ++ Seq(Dependencies.Http4s.dsl, Dependencies.Cats.catsEffectTesting),
  )
  .dependsOn(http, testkit % Test, cats % Test)

lazy val sttp = project
  .in(file("integrations/sttp"))
  .settings(commonSettings)
  .settings(
    name := "metrics4s-sttp",
    libraryDependencies ++= Dependencies.Sttp.all,
  )
  .dependsOn(http, testkit % Test, core % "test->test")

lazy val mongo = project
  .in(file("integrations/mongo"))
  .settings(commonSettings)
  .settings(
    name := "metrics4s-mongo",
    libraryDependencies ++= Dependencies.Mongo.all,
  )
  .dependsOn(core, testkit % Test, core % "test->test")

lazy val integrations = project
  .in(file("integrations"))
  .settings(commonSettings)
  .settings(aggregateSettings)
  .aggregate(http, http4s, tapir, sttp, mongo)

lazy val benchmarks = project
  .in(file("benchmarks"))
  .enablePlugins(JmhPlugin)
  .settings(commonSettings)
  .settings(aggregateSettings)
  .settings(
    name := "metrics4s-benchmarks"
  )
  .dependsOn(prometheus)

lazy val it = project
  .in(file("it"))
  .settings(commonSettings)
  .settings(aggregateSettings)
  .settings(
    name              := "metrics4s-it",
    scalaVersion      := Versions.scala3Next,
    sourceLevel       := "3.10",
    Test / fork       := true,
    parallelExecution := false,
    Test / envVars ++= sys.env
      .get("DOCKER_HOST")
      .map("DOCKER_HOST" -> _)
      .orElse(Option.when(scala.util.Properties.isWin)("DOCKER_HOST" -> "tcp://localhost:2375"))
      .toMap,
    Test / javaOptions ++= Seq(
      s"-Dmetrics4s.version=${version.value}",
      s"-Dmetrics4s.root=${(ThisBuild / baseDirectory).value}",
      s"-Dmetrics4s.examples=${(Test / scalaSource).value / "metrics4s" / "it" / "docs"}",
    ),
    libraryDependencies ++= Dependencies.IntegrationTesting.all ++ Seq(Dependencies.Tapir.http4sServer, Dependencies.Http4s.dsl),
  )
  .dependsOn(core, prometheus, cats, zio, kyo, rapid, testkit, grafana, http, http4s, tapir, sttp, mongo)

lazy val root = project
  .in(file("."))
  .settings(commonSettings)
  .settings(aggregateSettings)
  .settings(
    name := "metrics4s"
  )
  .aggregate(core, backend, runtime, testkit, grafana, integrations)
