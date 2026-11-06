package metrics4s.runtime.cats

trait CatsInstances extends SyncToEffectInstance, AsyncToDispatchInstance

object CatsInstances extends CatsInstances
