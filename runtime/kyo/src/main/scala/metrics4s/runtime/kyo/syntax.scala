package metrics4s.runtime.kyo

import kyo.{<, Abort, Async, Scope}

import metrics4s.Allocated

object syntax:
  trait AllocatedSyntax:
    extension [A](allocated: Allocated[KIO, A])
      def toScope: A < (Scope & Async & Abort[Throwable]) =
        Scope.acquireRelease(allocated.allocate)((_, release) => release).map((a, _) => a)

  given AllocatedSyntax = new AllocatedSyntax {}
