package metrics4s

opaque type Allocated[F[*], A] = F[(A, F[Unit])]

object Allocated:
  def apply[F[*], A](allocate: F[(A, F[Unit])]): Allocated[F, A] = allocate

  def make[F[*], A](acquire: F[A])(release: A => F[Unit])(using F: Effect[F]): Allocated[F, A] =
    F.map(acquire)(a => (a, release(a)))

  def eval[F[*], A](fa: F[A])(using F: Effect[F]): Allocated[F, A] =
    F.map(fa)(a => (a, F.unit))

  def pure[F[*], A](a: A)(using F: Effect[F]): Allocated[F, A] =
    F.pure((a, F.unit))

  extension [F[*], A](allocated: Allocated[F, A])
    def allocate: F[(A, F[Unit])] = allocated

    def map[B](f: A => B)(using F: Effect[F]): Allocated[F, B] =
      F.flatMap(allocated) { (a, release) =>
        F.onError(F.delay((f(a), release)))(_ => release)
      }

    def flatMap[B](f: A => Allocated[F, B])(using F: Effect[F]): Allocated[F, B] =
      F.flatMap(allocated) { (a, releaseA) =>
        val acquired = F.guaranteeCase(F.suspend(f(a).allocate)) {
          case ExitCase.Succeeded => F.unit
          case _                  => releaseA
        }

        F.map(acquired)((b, releaseB) => (b, F.guarantee(releaseB)(releaseA)))
      }

    def use[B](f: A => F[B])(using F: Effect[F]): F[B] =
      F.bracket(allocated)((a, _) => f(a))((_, release) => release)
