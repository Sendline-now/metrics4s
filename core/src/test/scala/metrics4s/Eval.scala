package metrics4s

import scala.util.control.NonFatal

type Eval[A] = () => A

object Eval:
  def run[A](fa: Eval[A]): A = fa()

  def attempt[A](fa: Eval[A]): Either[Throwable, A] =
    try Right(fa())
    catch case NonFatal(error) => Left(error)

  given Effect[Eval] = new Effect[Eval]:
    def pure[A](a: A): Eval[A]                                            = () => a
    def delay[A](a: => A): Eval[A]                                        = () => a
    def map[A, B](fa: Eval[A])(f: A => B): Eval[B]                        = () => f(fa())
    def flatMap[A, B](fa: Eval[A])(f: A => Eval[B]): Eval[B]              = () => f(fa())()
    def raiseError[A](error: Throwable): Eval[A]                          = () => throw error
    def handleErrorWith[A](fa: Eval[A])(f: Throwable => Eval[A]): Eval[A] =
      () =>
        try fa()
        catch case NonFatal(error) => f(error)()

    def guaranteeCase[A](fa: Eval[A])(finalizer: ExitCase => Eval[Unit]): Eval[A] =
      () =>
        val result = Eval.attempt(fa)
        runFinalizer(result.fold(ExitCase.Errored(_), _ => ExitCase.Succeeded), finalizer)()
        result.fold(error => throw error, identity)
