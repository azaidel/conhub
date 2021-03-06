package com.evolutiongaming.conhub

import com.evolutiongaming.concurrent.CurrentThreadExecutionContext
import com.evolutiongaming.concurrent.sequentially.{MapDirective, SequentialMap}
import com.evolutiongaming.concurrent.FutureHelper._

import scala.concurrent.Future

object SequentialMapHelper {

  implicit class SequentialMapOps[K, V](val self: SequentialMap[K, V]) extends AnyVal {

    def updateAndRun[T](key: K)(f: Option[V] => (MapDirective[V], () => T)): Future[T] = {
      val result = self.update(key)(f)
      // done in `mapNow` to ensure SequentialMap is already updated when this called
      result.mapNow { run => run() }
    }
  }


  implicit class SetValuesOps[K, V](val self: SequentialMap[K, Set[V]]) extends AnyVal {

    def updateSets(
      before: Option[K],
      after: Option[K],
      value: V,
      onUpdated: (K, Set[V], Set[V]) => Unit = (_, _, _) => ()): Future[Unit] = {

      implicit val ec = CurrentThreadExecutionContext

      if (before != after) {
        val futureBefore = before.fold(Future.unit) { key => updateSet(key)(_ - value, onUpdated(key, _, _)) }
        val futureAfter = after.fold(Future.unit) { key => updateSet(key)(_ + value, onUpdated(key, _, _)) }
        for {
          _ <- futureBefore
          _ <- futureAfter
        } yield ()
      } else {
        Future.unit
      }
    }

    def updateSet(key: K)(
      f: Set[V] => Set[V],
      onUpdated: (Set[V], Set[V]) => Unit = (_, _) => ()): Future[Unit] = {

      self.updateAndRun(key) { value =>
        val before = value getOrElse Set.empty
        val after = f(before)
        val directive = if (after.isEmpty) MapDirective.remove else MapDirective.update(after)
        val callback = () => onUpdated(before, after)
        (directive, callback)
      }
    }

    def getSet(key: K): Set[V] = self.values.getOrElse(key, Set.empty)
  }


  implicit class FutureOps[T](val self: Future[T]) extends AnyVal {

    // to execute f strictly in order of future origin
    def mapNow[TT](f: T => TT): Future[TT] = self.map(f)(CurrentThreadExecutionContext)
  }
}
