import java.util.concurrent.ForkJoinPool
import java.util.concurrent.atomic.AtomicReference

/*
 * 1. Théorie
 *
 * Références :
 * - Carl Hewitt : https://www.youtube.com/watch?v=7erJ1DV_Tlo
 * - Dale Schumacher : http://www.dalnefre.com
 *
 * Un acteur assure 3 fonctions :
 * - Traitement
 * - Stockage
 * - Communication
 *
 * Un acteur est une référence stable vers une entité mutable, associée à
 * un protocole permettant une communication asynchrone par messages
 *
 * A la réception d'un message, un acteur peut :
 * - Créer d'autres acteurs
 * - Envoyer des messages
 * - Définir son comportement pour le message suivant
 *
 *
 * 2. ?
 * 3. PROFIT
 *
 */

type Actor[Message] = Message => Unit

trait Behavior[Message] extends (Message => Behavior[Message])

val executor = ForkJoinPool.commonPool()

def actor[Message](initial: Behavior[Message]): Actor[Message] = {
  var behavior = initial
  val messages = new AtomicReference(Vector.empty[Message])
  (message: Message) => if (messages.getAndUpdate(_ :+ message).isEmpty)
    executor.execute(() => {
      do behavior = behavior(messages.get.head)
      while (messages.updateAndGet(_.tail).nonEmpty)
    })
}

def discard[Message]: Behavior[Message] = _ => discard

val ignore = actor(discard[Any])

type Action[T] = Actor[T] => Unit

def action[T](f: => T): Action[T] = cb => {
  def behavior: Behavior[Unit] = _ => {
    cb(f)
    discard
  }
  actor(behavior)(())
}

val job = action {
  Thread.sleep(1000)
  42
}

implicit class ActionOps[T](that: Action[T]) {
  def map[U](f: T => U): Action[U] = cb =>
    that(t => action(f(t))(cb))

  def flatMap[U](f: T => Action[U]): Action[U] = cb =>
    that(t => action(f(t))(_(cb)))

  def zip[U, R](other: Action[U])(f: (T, U) => R): Action[R] = cb => {
    val state = actor[Either[T, U]] {
      case Left(t) => {
        case Right(u) =>
          cb(f(t, u))
          discard
      }
      case Right(u) => {
        case Left(t) =>
          cb(f(t, u))
          discard
      }
    }
    that(Left[T, U] andThen state)
    other(Right[T, U] andThen state)
  }
}

val otherAction = for {
  x <- action(6)
  y <- action(x + 1)
} yield x * y

job.zip(job)(_ + _)(println)