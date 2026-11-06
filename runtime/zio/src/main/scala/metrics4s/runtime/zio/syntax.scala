package metrics4s.runtime.zio

import zio.{Scope, Task, ZIO}

import metrics4s.Allocated

object syntax:
  trait AllocatedSyntax:
    extension [A](allocated: Allocated[Task, A])
      def toScoped: ZIO[Scope, Throwable, A] =
        ZIO.acquireRelease(allocated.allocate)((_, release) => release.orDie).map((a, _) => a)

  given AllocatedSyntax = new AllocatedSyntax {}
