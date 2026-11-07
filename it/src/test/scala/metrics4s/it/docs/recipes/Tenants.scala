package metrics4s.it.docs.recipes.tenants

import metrics4s.*

final case class Tenant(id: String)

object Tenant:
  val Other: Tenant = Tenant("other")

final class TenantMetrics(configured: List[Tenant]):
  given LabelValue[Tenant] = LabelValue.enumerated(configured :+ Tenant.Other)(_.id)

  val invoices = Counter("billing_invoices", "Invoices issued by tenant").labelled[(tenant: Tenant)]

  def tenantOf(id: String): Tenant = configured.find(_.id == id).getOrElse(Tenant.Other)
