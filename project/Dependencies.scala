import sbt.*

object Dependencies {

  object Versions {
    val scala3     = "3.9.0"
    val scala3Next = "3.10.0"

    val prometheus = "1.10.0"

    val catsEffect        = "3.7.1"
    val catsEffectTesting = "1.8.0"

    val zio = "2.1.27"

    val kyo = "1.0.0-RC7"

    val rapid = "2.9.9"

    val scalatest = "3.2.20"

    val testcontainers = "2.0.5"
    val ujson          = "4.4.3"

    val http4s  = "0.23.38"
    val tapir   = "1.14.1"
    val sttp    = "4.1.1"
    val mongo   = "5.13.0"
    val mongo4s = "5.1.0"
  }

  object Http4s {
    val server = "org.http4s" %% "http4s-server" % Versions.http4s
    val client = "org.http4s" %% "http4s-client" % Versions.http4s
    val dsl    = "org.http4s" %% "http4s-dsl"    % Versions.http4s % Test

    val all: Seq[ModuleID] = Seq(server, client, dsl)
  }

  object Tapir {
    val server       = "com.softwaremill.sttp.tapir" %% "tapir-server"        % Versions.tapir
    val http4sServer = "com.softwaremill.sttp.tapir" %% "tapir-http4s-server" % Versions.tapir % Test

    val all: Seq[ModuleID] = Seq(server, http4sServer)
  }

  object Sttp {
    val core = "com.softwaremill.sttp.client4" %% "core" % Versions.sttp

    val all: Seq[ModuleID] = Seq(core)
  }

  object Mongo {
    val driverCore = "org.mongodb" % "mongodb-driver-core" % Versions.mongo

    val all: Seq[ModuleID] = Seq(driverCore)
  }

  object Prometheus {
    val core        = "io.prometheus" % "prometheus-metrics-core"                   % Versions.prometheus
    val textFormats = "io.prometheus" % "prometheus-metrics-exposition-textformats" % Versions.prometheus
    val httpServer  = "io.prometheus" % "prometheus-metrics-exporter-httpserver"    % Versions.prometheus
    val jvm         = "io.prometheus" % "prometheus-metrics-instrumentation-jvm"    % Versions.prometheus

    val all: Seq[ModuleID] = Seq(core, textFormats, httpServer, jvm)
  }

  object Cats {
    val catsEffect        = "org.typelevel" %% "cats-effect"                   % Versions.catsEffect
    val catsEffectTesting = "org.typelevel" %% "cats-effect-testing-scalatest" % Versions.catsEffectTesting % Test

    val all: Seq[ModuleID] = Seq(catsEffect, catsEffectTesting)
  }

  object Zio {
    val zio = "dev.zio" %% "zio" % Versions.zio

    val all: Seq[ModuleID] = Seq(zio)
  }

  object Kyo {
    val kyoCore = ("io.getkyo" %% "kyo-core" % Versions.kyo).excludeAll(ExclusionRule("org.openjdk.jmh"))

    val all: Seq[ModuleID] = Seq(kyoCore)
  }

  object Rapid {
    val rapidCore = "com.outr" %% "rapid-core" % Versions.rapid

    val all: Seq[ModuleID] = Seq(rapidCore)
  }

  object IntegrationTesting {
    val testcontainers      = "org.testcontainers" % "testcontainers"         % Versions.testcontainers % Test
    val testcontainersMongo = "org.testcontainers" % "testcontainers-mongodb" % Versions.testcontainers % Test
    val ujson               = "com.lihaoyi"       %% "ujson"                  % Versions.ujson          % Test
    val mongo4s             = "org.mongo4s"       %% "mongo4s-cats"           % Versions.mongo4s        % Test

    val all: Seq[ModuleID] = Seq(testcontainers, testcontainersMongo, ujson, mongo4s)
  }

  object Testing {
    val scalatest = "org.scalatest" %% "scalatest" % Versions.scalatest % Test
    val scalactic = "org.scalactic" %% "scalactic" % Versions.scalatest % Test

    val all: Seq[ModuleID] = Seq(scalatest, scalactic)
  }

}
