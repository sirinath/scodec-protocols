package scodec.protocols

import language.higherKinds

import scala.concurrent.duration._

import scalaz.{ \/, \/-, -\/, Applicative, Lens, LensFamily, Monoid, Traverse, Monad }
import \/.{ left, right }
import scalaz.concurrent.{ Strategy, Task }
import scalaz.syntax.applicative._
import scalaz.syntax.monoid._
import scalaz.stream._
import Process._

import java.util.concurrent.ScheduledExecutorService

import org.joda.time.{ DateTime, DateTimeZone, Duration => JDuration }

/** Wrapper that associates a time with a value. */
case class TimeStamped[+A](time: DateTime, value: A) {
  def map[B](f: A => B): TimeStamped[B] = copy(value = f(value))
  def mapTime(f: DateTime => DateTime): TimeStamped[A] = copy(time = f(time))
}

object TimeStamped {

  def now[A](a: A): TimeStamped[A] = TimeStamped(DateTime.now(DateTimeZone.UTC), a)

  object Lenses {
    def TimeStamp[A]: Lens[TimeStamped[A], DateTime] = Lens.lensu((t, s) => t.copy(time = s), _.time)
    def Value[A]: Lens[TimeStamped[A], A] = Lens.lensu((t, a) => t.copy(value = a), _.value)

    def ValueMap[A, B]: LensFamily[TimeStamped[A], TimeStamped[B], A, B] =
      Lens.lensFamilyu((tsa, b) => TimeStamped(tsa.time, b), _.value)
  }

  /** Orders values by timestamp -- values with the same timestamp are considered equal. */
  def timeBasedOrdering[A]: Ordering[TimeStamped[A]] = new Ordering[TimeStamped[A]] {
    def compare(x: TimeStamped[A], y: TimeStamped[A]) = x.time compareTo y.time
  }

  /** Orders values by timestamp, then by value. */
  implicit def ordering[A](implicit A: Ordering[A]): Ordering[TimeStamped[A]] = new Ordering[TimeStamped[A]] {
    def compare(x: TimeStamped[A], y: TimeStamped[A]) = x.time compareTo y.time match {
      case 0 => A.compare(x.value, y.value)
      case other => other
    }
  }

  implicit def traverseInstance: Traverse[TimeStamped] = new Traverse[TimeStamped] {
    def traverseImpl[G[_], A, B](ta: TimeStamped[A])(f: A => G[B])(implicit G: Applicative[G]): G[TimeStamped[B]] =
      f(ta.value) map { b => TimeStamped(ta.time, b) }
  }

  implicit def monadInstance: Monad[TimeStamped] = new Monad[TimeStamped] {
    def point[A](a: => A) = TimeStamped.now(a)
    def bind[A, B](fa: TimeStamped[A])(f: A => TimeStamped[B]): TimeStamped[B] = f(fa.value)
  }

  /**
   * Combinator that converts a `Process1[A, B]` in to a `Process1[TimeStamped[A], TimeStamped[B]]` such that
   * timestamps are preserved on elements that flow through the process.
   */
  def preserveTimeStamps[A, B](p: Process1[A, B]): Process1[TimeStamped[A], TimeStamped[B]] =
    process1ext.lensf(Lenses.ValueMap[A, B])(p)

  /**
   * Stream transducer that converts a stream of `TimeStamped[A]` in to a stream of
   * `TimeStamped[B]` where `B` is an accumulated feature of `A` over a second.
   *
   * For example, the emitted bits per second of a `Process[Task, ByteVector]` can be calculated
   * using `perSecondRate(_.size * 8)`, which yields a stream of the emitted bits per second.
   *
   * @param f function which extracts a feature of `A`
   */
  def perSecondRate[A, B: Monoid](f: A => B): Process1[TimeStamped[A], TimeStamped[B]] =
    rate(1.second)(f)

  /**
   * Stream transducer that converts a stream of `TimeStamped[A]` in to a stream of
   * `TimeStamped[B \/ A]` where `B` is an accumulated feature of `A` over a second.
   *
   * Every incoming `A` is echoed to the output.
   *
   * For example, the emitted bits per second of a `Process[Task, ByteVector]` can be calculated
   * using `perSecondRate(_.size * 8)`, which yields a stream of the emitted bits per second.
   *
   * @param f function which extracts a feature of `A`
   */
  def withPerSecondRate[A, B: Monoid](f: A => B): Process1[TimeStamped[A], TimeStamped[B \/ A]] =
    withRate(1.second)(f)

  /**
   * Stream transducer that converts a stream of `TimeStamped[A]` in to a stream of
   * `TimeStamped[B]` where `B` is an accumulated feature of `A` over a specified time period.
   *
   * For example, the emitted bits per second of a `Process[Task, ByteVector]` can be calculated
   * using `rate(1.0)(_.size * 8)`, which yields a stream of the emitted bits per second.
   *
   * @param over time period over which to calculate
   * @param f function which extracts a feature of `A`
   */
  def rate[A, B: Monoid](over: FiniteDuration)(f: A => B): Process1[TimeStamped[A], TimeStamped[B]] =
    withRate(over)(f) pipe process1ext.drainFR

  /**
   * Stream transducer that converts a stream of `TimeStamped[A]` in to a stream of
   * `TimeStamped[B \/ A]` where `B` is an accumulated feature of `A` over a specified time period.
   *
   * Every incoming `A` is echoed to the output.
   *
   * For example, the emitted bits per second of a `Process[Task, ByteVector]` can be calculated
   * using `rate(1.0)(_.size * 8)`, which yields a stream of the emitted bits per second.
   *
   * @param over time period over which to calculate
   * @param f function which extracts a feature of `A`
   */
  def withRate[A, B: Monoid](over: FiniteDuration)(f: A => B): Process1[TimeStamped[A], TimeStamped[B \/ A]] = {
    val jodaOver = new JDuration(over.toMillis)
    def go(start: DateTime, acc: B): Process1[TimeStamped[A], TimeStamped[B \/ A]] = {
      val end = start plus jodaOver
      receive1Or[TimeStamped[A], TimeStamped[B \/ A]](emit(TimeStamped(end, left(acc)))) {
        case t @ TimeStamped(time, a) =>
          if (time isBefore end) emit(t map right) ++ go(start, acc |+| f(a))
          else emit(TimeStamped(end, left(acc))) ++ process1.feed1(t)(go(end, Monoid[B].zero))
      }
    }
    await1[TimeStamped[A]].flatMap { first =>
      emit(first map right) ++ go(first.time, f(first.value))
    }
  }

  /**
   * Returns a process that is the throttled version of the source process.
   *
   * Given two adjacent items from the source process, `a` and `b`, where `a` is emitted
   * first and `b` is emitted second, their time delta is `b.time - a.time`.
   *
   * This function creates a process that emits values at wall clock times such that
   * the time delta between any two adjacent values is proportional to their time delta
   * in the source process.
   *
   * The `throttlingFactor` is a scaling factor that determines how much source time a unit
   * of wall clock time is worth. A value of 1.0 causes the output process to emit
   * values spaced in wall clock time equal to their time deltas. A value of 2.0
   * emits values at twice the speed of wall clock time.
   *
   * This is particularly useful when timestamped data can be read in bulk (e.g., from a capture file)
   * but should be "played back" at real time speeds.
   */
  def throttle[A](source: Process[Task, TimeStamped[A]], throttlingFactor: Double)(implicit S: Strategy, scheduler: ScheduledExecutorService): Process[Task, TimeStamped[A]] = {
    import wye._

    val tickDuration = 100.milliseconds
    val ticksPerSecond = 1.second.toMillis / tickDuration.toMillis

    def doThrottle: Wye[TimeStamped[A], Duration, TimeStamped[A]] = {
      def read(upto: DateTime): Wye[TimeStamped[A], Duration, TimeStamped[A]] = {
        receiveL { tsa =>
          if (tsa.time.getMillis <= upto.getMillis) emit(tsa) ++ read(upto)
          else awaitTick(upto, tsa)
        }
      }

      def awaitTick(upto: DateTime, pending: TimeStamped[A]): Wye[TimeStamped[A], Duration, TimeStamped[A]] = {
        receiveR { tick =>
          val newUpto = upto plus ((1000 / ticksPerSecond) * throttlingFactor).toLong
          if (pending.time.getMillis < newUpto.getMillis) emit(pending) ++ read(newUpto)
          else awaitTick(newUpto, pending)
        }
      }

      receiveL { tsa => emit(tsa) ++ read(tsa.time) }
    }

    (source wye time.awakeEvery(tickDuration))(doThrottle)
  }

  /**
   * Stream transducer that filters the specified timestamped values to ensure
   * the output time stamps are always increasing in time. Other values are
   * dropped.
   */
  def increasing[A]: Process1[TimeStamped[A], TimeStamped[A]] = increasingW.stripW

  /**
   * Stream transducer that filters the specified timestamped values to ensure
   * the output time stamps are always increasing in time. The increasing values
   * are emitted as output of the writer, while out of order values are written
   * to the writer side of the writer.
   */
  def increasingW[A]: Writer1[TimeStamped[A], TimeStamped[A], TimeStamped[A]] = {
    def notBefore(last: DateTime): Writer1[TimeStamped[A], TimeStamped[A], TimeStamped[A]] = {
      await1[TimeStamped[A]] flatMap { t =>
        val now = t.time
        if (last.getMillis <= now.getMillis) emitO(t) ++ notBefore(now)
        else emitW(t) ++ notBefore(last)
      }
    }
    await1[TimeStamped[A]] flatMap { t => emitO(t) ++ notBefore(t.time) }
  }

  /**
   * Stream transducer that reorders a stream of timestamped values that are mostly ordered,
   * using a time based buffer of the specified duration. See [[attemptReorderLocally]] for details.
   *
   * The resulting process is guaranteed to always emit values in time increasing order.
   * Values may be dropped from the source process if they were not successfully reordered.
   */
  def reorderLocally[A](over: FiniteDuration): Process1[TimeStamped[A], TimeStamped[A]] = reorderLocallyW(over).stripW

  /**
   * Stream transducer that reorders a stream of timestamped values that are mostly ordered,
   * using a time based buffer of the specified duration. See [[attemptReorderLocally]] for details.
   *
   * The resulting process is guaranteed to always emit output values in time increasing order.
   * Any values that could not be reordered due to insufficient buffer space are emitted on the writer (left)
   * side.
   */
  def reorderLocallyW[A](over: FiniteDuration): Writer1[TimeStamped[A], TimeStamped[A], TimeStamped[A]] =
    attemptReorderLocally(over) pipe increasingW

  /**
   * Stream transducer that reorders timestamped values over a specified duration.
   *
   * Values are kept in an internal buffer. Upon receiving a new value, any buffered
   * values that are timestamped with `value.time - over` are emitted. Other values,
   * and the new value, are kept in the buffer.
   *
   * This is useful for ordering mostly ordered streams, where values
   * may be out of order with close neighbors but are strictly less than values
   * that come much later in the stream.
   *
   * An example of such a structure is the result of merging streams of values generated
   * with `TimeStamped.now`.
   *
   * Caution: this transducer should only be used on streams that are mostly ordered.
   * In the worst case, if the source is in reverse order, all values in the source
   * will be accumulated in to the buffer until the source halts, and then the{
   * values will be emitted in order.
   */
  def attemptReorderLocally[A](over: FiniteDuration): Process1[TimeStamped[A], TimeStamped[A]] = {
    import scala.collection.immutable.SortedMap
    val overMillis = over.toMillis

    def emitMapValues(m: SortedMap[Long, Vector[TimeStamped[A]]]) =
      emitAll(m.foldLeft(Vector.empty[TimeStamped[A]]) { case (acc, (_, tss)) => acc ++ tss })

    def go(buffered: SortedMap[Long, Vector[TimeStamped[A]]]): Process1[TimeStamped[A], TimeStamped[A]] = {
      receive1Or[TimeStamped[A], TimeStamped[A]](emitMapValues(buffered)) { t =>
        val until = t.time.getMillis - overMillis
        val (toEmit, toBuffer) = buffered span { case (x, _) => x <= until }
        val updatedBuffer = toBuffer + (t.time.getMillis -> (toBuffer.getOrElse(t.time.getMillis, Vector.empty[TimeStamped[A]]) :+ t))
        emitMapValues(toEmit) ++ go(updatedBuffer)
      }
    }
    go(SortedMap.empty)
  }

  /** `TimeStamped` version of [[scalaz.stream.process1.liftL]]. */
  def liftL[A, B, C](p: Process1[TimeStamped[A], TimeStamped[B]]): Process1[TimeStamped[A \/ C], TimeStamped[B \/ C]] = {
    def go(cur: Process1[TimeStamped[A], TimeStamped[B]]): Process1[TimeStamped[A \/ C], TimeStamped[B \/ C]] = {
      receive1Or[TimeStamped[A \/ C], TimeStamped[B \/ C]](cur.disconnect(Cause.Kill).map { _ map left }) {
        case t @ TimeStamped(time, \/-(c)) =>
          emit(t.asInstanceOf[TimeStamped[B \/ C]]) ++ go(cur)
        case TimeStamped(time, -\/(a)) =>
          val (toEmit, next) = cur.feed1(TimeStamped(time, a)).unemit
          val out = toEmit map { _ map left }
          emitAll(out) ++ (next match {
            case h @ Halt(_) => h
            case _ => go(next)
          })
      }
    }
    go(p)
  }

  /** `TimeStamped` version of [[scalaz.stream.process1.liftR]]. */
  def liftR[A, B, C](p: Process1[TimeStamped[A], TimeStamped[B]]): Process1[TimeStamped[C \/ A], TimeStamped[C \/ B]] =
    process1.lift((_: TimeStamped[C \/ A]) map { _.swap }).pipe(liftL(p)).map { _ map { _.swap } }
}
