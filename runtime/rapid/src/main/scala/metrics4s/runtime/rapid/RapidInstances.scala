package metrics4s.runtime.rapid

trait RapidInstances extends TaskToEffectInstance, TaskToDispatchInstance

object RapidInstances extends RapidInstances
