package metrics4s.mongo

import java.util.HashMap
import java.util.concurrent.{ConcurrentHashMap, TimeUnit}

import scala.concurrent.duration.{FiniteDuration, NANOSECONDS}

import com.mongodb.MongoClientSettings
import com.mongodb.event.*
import org.bson.BsonDocument

import metrics4s.*

enum Command derives LabelValue:
  case Find, Insert, Update, Delete, Aggregate, Count, Distinct, GetMore, FindAndModify, BulkWrite, CreateIndexes, CommitTransaction, AbortTransaction, Other

object Command:
  private val byName: Map[String, Command] = Map(
    "find"              -> Find,
    "insert"            -> Insert,
    "update"            -> Update,
    "delete"            -> Delete,
    "aggregate"         -> Aggregate,
    "count"             -> Count,
    "distinct"          -> Distinct,
    "getMore"           -> GetMore,
    "findAndModify"     -> FindAndModify,
    "bulkWrite"         -> BulkWrite,
    "createIndexes"     -> CreateIndexes,
    "commitTransaction" -> CommitTransaction,
    "abortTransaction"  -> AbortTransaction,
  )

  def of(name: String): Command = byName.getOrElse(name, Other)

final case class Collection(name: String)

object Collection:
  val Other: Collection = Collection("other")

enum CheckOutFailure derives LabelValue:
  case PoolClosed, Timeout, ConnectionError, Unknown

object CheckOutFailure:
  def of(reason: ConnectionCheckOutFailedEvent.Reason): CheckOutFailure =
    reason match
      case ConnectionCheckOutFailedEvent.Reason.POOL_CLOSED      => PoolClosed
      case ConnectionCheckOutFailedEvent.Reason.TIMEOUT          => Timeout
      case ConnectionCheckOutFailedEvent.Reason.CONNECTION_ERROR => ConnectionError
      case _                                                     => Unknown

enum ConnectionState derives LabelValue:
  case Open, CheckedOut

final class MongoMetrics private (val collections: List[Collection]):
  private val known = HashMap[String, Collection]()
  collections.foreach(collection => known.put(collection.name, collection): Unit)

  private given LabelValue[Collection] = LabelValue.enumerated(collections :+ Collection.Other)(_.name)

  val commands: HistogramDef[FiniteDuration, (command: Command, collection: Collection)] =
    MongoMetrics.commands.labelled[(command: Command, collection: Collection)]

  val failures: CounterDef[Long, (command: Command, collection: Collection)] = MongoMetrics.failures.labelled[(command: Command, collection: Collection)]

  val connections: GaugeDef[Double, (state: ConnectionState)] = MongoMetrics.connections

  val checkOut: HistogramDef[FiniteDuration, NoLabels] = MongoMetrics.checkOut

  val checkOutFailures: CounterDef[Long, (reason: CheckOutFailure)] = MongoMetrics.checkOutFailures

  val definitions: List[MetricDef] = List(commands, failures, connections, checkOut, checkOutFailures)

  def collection(name: Option[String]): Collection = name.fold(Collection.Other)(known.getOrDefault(_, Collection.Other))

  def register[F[*]](using metrics: Metrics[F], F: Effect[F]): F[MongoListeners] =
    F.map(metrics.registerAll((commands, failures, connections, checkOut, checkOutFailures))) { (commands, failures, connections, checkOut, checkOutFailures) =>
      MongoListeners(this, commands.unsafe, failures.unsafe, connections.unsafe, checkOut.unsafe, checkOutFailures.unsafe)
    }

object MongoMetrics:
  private given LabelValue[Collection] = LabelValue.bounded(_.name)

  val commands: HistogramDef[FiniteDuration, (command: Command, collection: Collection)] =
    Histogram
      .seconds("mongo_command_seconds", "Time of a command, from sending it to its reply", Buckets.Normal)
      .labelled[(command: Command, collection: Collection)]
      .withoutPreCreation

  val failures: CounterDef[Long, (command: Command, collection: Collection)] =
    Counter("mongo_command_failures", "Commands the server or the driver failed")
      .labelled[(command: Command, collection: Collection)]
      .withPreCreation(PreCreation.WithinLimit)

  val connections: GaugeDef[Double, (state: ConnectionState)] =
    Gauge("mongo_pool_connections", "Connections of the pools, open and checked out").labelled[(state: ConnectionState)]

  val checkOut: HistogramDef[FiniteDuration, NoLabels] =
    Histogram.seconds("mongo_pool_checkout_seconds", "Time to check a connection out of the pool", Buckets.Fast)

  val checkOutFailures: CounterDef[Long, (reason: CheckOutFailure)] =
    Counter("mongo_pool_checkout_failures", "Connection check-outs that failed, by reason").labelled[(reason: CheckOutFailure)]

  val definitions: List[MetricDef] = List(commands, failures, connections, checkOut, checkOutFailures)

  def apply(collections: Seq[String]): MongoMetrics =
    new MongoMetrics(collections.distinct.filterNot(_ == Collection.Other.name).map(Collection(_)).toList)

final class MongoListeners private[mongo] (
    metrics: MongoMetrics,
    commands: HistogramOf.Unsafe[FiniteDuration, (command: Command, collection: Collection)],
    failures: CounterOf.Unsafe[Long, (command: Command, collection: Collection)],
    connections: GaugeOf.Unsafe[Double, (state: ConnectionState)],
    checkOut: HistogramOf.Unsafe[FiniteDuration, NoLabels],
    checkOutFailures: CounterOf.Unsafe[Long, (reason: CheckOutFailure)],
):
  private val started = ConcurrentHashMap[Integer, (command: Command, collection: Collection)]()

  val command: CommandListener = new CommandListener:
    override def commandStarted(event: CommandStartedEvent): Unit =
      started.put(
        event.getRequestId,
        (command = Command.of(event.getCommandName), collection = metrics.collection(collectionOf(event.getCommandName, event.getCommand)))
      ): Unit

    override def commandSucceeded(event: CommandSucceededEvent): Unit =
      finish(event.getRequestId, event.getCommandName, event.getElapsedTime(TimeUnit.NANOSECONDS), failed = false)

    override def commandFailed(event: CommandFailedEvent): Unit =
      finish(event.getRequestId, event.getCommandName, event.getElapsedTime(TimeUnit.NANOSECONDS), failed = true)

  val pool: ConnectionPoolListener = new ConnectionPoolListener:
    override def connectionCreated(event: ConnectionCreatedEvent): Unit = connections.inc((state = ConnectionState.Open))

    override def connectionClosed(event: ConnectionClosedEvent): Unit = connections.dec((state = ConnectionState.Open))

    override def connectionCheckedOut(event: ConnectionCheckedOutEvent): Unit =
      connections.inc((state = ConnectionState.CheckedOut))
      checkOut.observe(FiniteDuration(event.getElapsedTime(TimeUnit.NANOSECONDS), NANOSECONDS))

    override def connectionCheckedIn(event: ConnectionCheckedInEvent): Unit = connections.dec((state = ConnectionState.CheckedOut))

    override def connectionCheckOutFailed(event: ConnectionCheckOutFailedEvent): Unit =
      checkOutFailures.inc((reason = CheckOutFailure.of(event.getReason)))

  def applyTo(builder: MongoClientSettings.Builder): MongoClientSettings.Builder =
    builder.addCommandListener(command).applyToConnectionPoolSettings(_.addConnectionPoolListener(pool): Unit)

  private def finish(requestId: Int, commandName: String, elapsedNanos: Long, failed: Boolean): Unit =
    val labels = Option(started.remove(requestId)).getOrElse((command = Command.of(commandName), collection = Collection.Other))
    commands.observe(labels, FiniteDuration(elapsedNanos, NANOSECONDS))
    if failed then failures.inc(labels)

  private def collectionOf(commandName: String, command: BsonDocument): Option[String] =
    val field = if commandName == "getMore" then "collection" else commandName
    Option(command.get(field)).filter(_.isString).map(_.asString.getValue)
