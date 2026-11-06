package metrics4s

enum Outcome derives LabelValue:
  case Ok, Skipped, Error

object Outcome:
  given Labels[Outcome] = Labels.single("outcome")

enum Source derives LabelValue:
  case Kafka, Rabbit

enum HttpError derives LabelValue:
  case NotFound, HTTPVersionNotSupported, Timeout2Late

final case class ByOutcome(outcome: Outcome, source: Source) derives Labels

final case class Provider(name: String)

object Provider:
  given LabelValue[Provider] = LabelValue.bounded(_.name)
  given Labels[Provider]     = Labels.single("provider")

final case class ByProvider(provider: Provider, outcome: Outcome) derives Labels
