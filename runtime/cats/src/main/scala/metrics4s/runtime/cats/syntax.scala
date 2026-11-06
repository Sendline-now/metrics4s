package metrics4s.runtime.cats

import cats.Functor
import cats.effect.kernel.Resource

import metrics4s.Allocated

object syntax:
  trait AllocatedSyntax:
    extension [F[*], A](allocated: Allocated[F, A])
      def toResource(using Functor[F]): Resource[F, A] =
        Resource.make(allocated.allocate)((_, release) => release).map((a, _) => a)

  given AllocatedSyntax = new AllocatedSyntax {}
