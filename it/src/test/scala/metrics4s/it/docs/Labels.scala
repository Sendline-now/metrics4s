package metrics4s.it.docs.labels

import metrics4s.*

enum Outcome derives LabelValue:
  case Ok, Skipped, Error

enum SkipReason derives LabelValue:
  case Duplicate, Expired

enum Source derives LabelValue:
  case Kafka, Rabbit

final case class ByOutcome(outcome: Outcome, source: Source) derives Labels

val bySource = Counter("processor_source_items", "Items by outcome and source").labelled[ByOutcome]
val skipped  = Counter("processor_skipped", "Skipped items by reason").labelled[(reason: SkipReason)]
