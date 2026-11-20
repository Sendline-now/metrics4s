package metrics4s.it

import org.scalatest.BeforeAndAfterAll
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import cats.effect.IO
import com.mongodb.{ConnectionString, MongoClientSettings}
import mongo4s.RsBridgeConfig
import mongo4s.bson.direct.WireCodec
import mongo4s.cats.MongoClientResource
import org.testcontainers.mongodb.MongoDBContainer

import metrics4s.Metrics
import metrics4s.mongo.{Collection, Command, ConnectionState, MongoMetrics}
import metrics4s.testkit.MetricsTestkit

import cats.effect.unsafe.implicits.global
import metrics4s.runtime.cats.CatsInstances.given

final case class User(id: String, name: String) derives WireCodec

final class MongoItSpec extends AnyWordSpec, Matchers, BeforeAndAfterAll:
  private val container = MongoDBContainer("mongo:9.0")

  given RsBridgeConfig = RsBridgeConfig.default

  override def beforeAll(): Unit = container.start()

  override def afterAll(): Unit = container.stop()

  "the MongoDB listeners on a real server through mongo4s" should {
    "time the commands mongo4s sends and follow its connection pool" in {
      val testkit       = MetricsTestkit()
      given Metrics[IO] = testkit.metrics[IO]
      val defs          = MongoMetrics(List("users"))

      val (found, openDuringUse) = (for
        listeners <- defs.register[IO]
        settings   = listeners.applyTo(MongoClientSettings.builder().applyConnectionString(ConnectionString(container.getConnectionString))).build()
        result    <- MongoClientResource.fromSettings[IO](settings).use { client =>
                       for
                         database <- client.getDatabase("it")
                         users    <- database.getDirectCollection[User]("users")
                         _        <- users.insertOne(User("1", "Alice"))
                         _        <- users.insertOne(User("2", "Bob"))
                         found    <- users.find().first
                         open     <- IO(testkit.value(defs.connections, (state = ConnectionState.Open)))
                       yield (found, open)
                     }
      yield result).unsafeRunSync()

      (
        found.map(_.name),
        testkit.histogram(defs.commands, (command = Command.Insert, collection = Collection("users"))).map(_.count),
        testkit.histogram(defs.commands, (command = Command.Find, collection = Collection("users"))).map(_.count),
        openDuringUse.exists(_ >= 1),
        testkit.value(defs.connections, (state = ConnectionState.Open)),
        testkit.value(defs.connections, (state = ConnectionState.CheckedOut)),
        testkit.histogram(defs.checkOut).exists(_.count >= 3),
      ) shouldBe (Some("Alice"), Some(2L), Some(1L), true, Some(0.0), Some(0.0), true)
    }
  }
