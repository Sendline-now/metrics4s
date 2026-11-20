package metrics4s.mongo

import java.util.concurrent.TimeUnit

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import com.mongodb.ServerAddress
import com.mongodb.connection.{ClusterId, ConnectionDescription, ConnectionId, ServerId}
import com.mongodb.event.*
import org.bson.{BsonDocument, BsonInt32, BsonString}

import metrics4s.{Eval, MetricDef, Metrics, MetricsConfig}
import metrics4s.grafana.{Dashboard, Row, Rows, ServiceFilter}
import metrics4s.testkit.MetricsTestkit

import metrics4s.Eval.given

final class MongoMetricsSpec extends AnyWordSpec, Matchers:
  private val server      = ServerId(ClusterId(), ServerAddress("localhost", 27017))
  private val description = ConnectionDescription(server)
  private val connection  = ConnectionId(server)

  private def command(name: String, collection: String): BsonDocument =
    BsonDocument(name, BsonString(collection)).append("$db", BsonString("app"))

  private def nanos(millis: Long): Long = TimeUnit.MILLISECONDS.toNanos(millis)

  "the MongoDB listeners" should {
    "time commands by command and collection, folding unknown collections into other, and count the failed ones" in {
      val testkit         = MetricsTestkit()
      given Metrics[Eval] = testkit.metrics[Eval]
      val defs            = MongoMetrics(List("users"))
      val listeners       = Eval.run(defs.register[Eval])

      listeners.command.commandStarted(CommandStartedEvent(null, 1, 10, description, "app", "find", command("find", "users")))
      listeners.command.commandSucceeded(CommandSucceededEvent(null, 1, 10, description, "app", "find", BsonDocument("ok", BsonInt32(1)), nanos(30)))
      listeners.command.commandStarted(CommandStartedEvent(null, 2, 11, description, "app", "insert", command("insert", "audit")))
      listeners.command.commandFailed(CommandFailedEvent(null, 2, 11, description, "app", "insert", nanos(5), IllegalStateException("duplicate key")))
      listeners.command.commandStarted(
        CommandStartedEvent(null, 3, 12, description, "app", "getMore", BsonDocument("getMore", BsonInt32(7)).append("collection", BsonString("users")))
      )
      listeners.command.commandSucceeded(CommandSucceededEvent(null, 3, 12, description, "app", "getMore", BsonDocument("ok", BsonInt32(1)), nanos(2)))

      val find = testkit.histogram(defs.commands, (command = Command.Find, collection = Collection("users")))

      (
        find.map(_.count),
        find.map(_.sum),
        testkit.histogram(defs.commands, (command = Command.Insert, collection = Collection.Other)).map(_.count),
        testkit.value(defs.failures, (command = Command.Insert, collection = Collection.Other)),
        testkit.histogram(defs.commands, (command = Command.GetMore, collection = Collection("users"))).map(_.count),
        testkit.value(defs.failures, (command = Command.Find, collection = Collection("users"))),
      ) shouldBe (Some(1L), Some(0.03), Some(1L), Some(1.0), Some(1L), Some(0.0))
    }

    "show every failure counter at zero from the start, so the first failure is a rise and not a new series" in {
      val testkit         = MetricsTestkit()
      given Metrics[Eval] = testkit.metrics[Eval]
      val defs            = MongoMetrics(List("users", "orders"))
      val _               = Eval.run(defs.register[Eval])

      (
        testkit.labelValues(defs.failures).size,
        testkit.labelValues(defs.failures).map(_.last),
        testkit.labelValues(defs.commands),
      ) shouldBe (Command.values.length * 3, Set("users", "orders", "other"), Set.empty)
    }

    "create the failure counters on first use where the collections are too many to create them all" in {
      val testkit         = MetricsTestkit()
      given Metrics[Eval] = testkit.metrics[Eval](MetricsConfig.default.withPreCreateLimit(20))
      val defs            = MongoMetrics(List("users", "orders"))
      val listeners       = Eval.run(defs.register[Eval])
      val before          = testkit.labelValues(defs.failures)

      listeners.command.commandStarted(CommandStartedEvent(null, 1, 10, description, "app", "find", command("find", "orders")))
      listeners.command.commandFailed(CommandFailedEvent(null, 1, 10, description, "app", "find", nanos(5), IllegalStateException("cursor not found")))

      (before, testkit.labelValues(defs.failures)) shouldBe (Set.empty, Set(List("find", "orders")))
    }

    "name the commands of a transaction and of a bulk write, which belong to no collection" in {
      val testkit         = MetricsTestkit()
      given Metrics[Eval] = testkit.metrics[Eval]
      val defs            = MongoMetrics(List("users"))
      val listeners       = Eval.run(defs.register[Eval])

      List("commitTransaction", "abortTransaction", "bulkWrite", "listIndexes").zipWithIndex.foreach { (name, index) =>
        listeners.command.commandStarted(CommandStartedEvent(null, index, 10, description, "admin", name, BsonDocument(name, BsonInt32(1))))
        listeners.command.commandSucceeded(CommandSucceededEvent(null, index, 10, description, "admin", name, BsonDocument("ok", BsonInt32(1)), nanos(1)))
      }

      testkit.labelValues(defs.commands) shouldBe Set(
        List("commit_transaction", "other"),
        List("abort_transaction", "other"),
        List("bulk_write", "other"),
        List("other", "other"),
      )
    }

    "follow the pool: connections open and checked out, check-out time and failures by reason" in {
      val testkit         = MetricsTestkit()
      given Metrics[Eval] = testkit.metrics[Eval]
      val defs            = MongoMetrics(Nil)
      val listeners       = Eval.run(defs.register[Eval])

      listeners.pool.connectionCreated(ConnectionCreatedEvent(connection))
      listeners.pool.connectionCreated(ConnectionCreatedEvent(connection))
      listeners.pool.connectionCheckedOut(ConnectionCheckedOutEvent(connection, 1, nanos(3)))
      listeners.pool.connectionCheckedOut(ConnectionCheckedOutEvent(connection, 2, nanos(1)))
      listeners.pool.connectionCheckedIn(ConnectionCheckedInEvent(connection, 1))
      listeners.pool.connectionClosed(ConnectionClosedEvent(connection, ConnectionClosedEvent.Reason.IDLE))
      listeners.pool.connectionCheckOutFailed(ConnectionCheckOutFailedEvent(server, 3, ConnectionCheckOutFailedEvent.Reason.TIMEOUT, nanos(100)))

      (
        testkit.value(defs.connections, (state = ConnectionState.Open)),
        testkit.value(defs.connections, (state = ConnectionState.CheckedOut)),
        testkit.histogram(defs.checkOut).map(_.count),
        testkit.value(defs.checkOutFailures, (reason = CheckOutFailure.Timeout)),
        testkit.value(defs.checkOutFailures, (reason = CheckOutFailure.PoolClosed)),
        testkit.missingSeries(Dashboard("api", "Line / api", "line", ServiceFilter.regex("api"))(Rows.mongo)),
      ) shouldBe (
        Some(1.0),
        Some(1.0),
        Some(2L),
        Some(1.0),
        Some(0.0),
        Set("mongo_command_seconds_count", "mongo_command_seconds_bucket")
      )
    }
  }

  "the MongoDB definitions" should {
    "describe the same series without the collections as with them, and serve every series the shared row reads" in {
      def shape(definition: MetricDef) =
        (definition.name.value, definition.help, definition.kind, definition.unit, definition.labelNames.toList, definition.preCreation)

      (
        MongoMetrics.definitions.map(shape),
        Dashboard("api", "Line / api", "line", ServiceFilter.regex("api"))(Row("MongoDB")(Rows.mongo.panels*)).missingSeries(MongoMetrics.definitions),
      ) shouldBe (
        MongoMetrics(List("users")).definitions.map(shape),
        Set.empty,
      )
    }
  }
