package metrics4s.runtime.zio

trait ZioInstances extends TaskToEffectInstance, TaskToDispatchInstance

object ZioInstances extends ZioInstances
