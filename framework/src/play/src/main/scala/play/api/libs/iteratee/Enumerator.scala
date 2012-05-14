package play.api.libs.iteratee

import play.api.libs.concurrent._

/**
 * Pushes input to an [[play.api.libs.iteratee.Iteratee]]
 * @type E Type of the input
 */

trait Enumerator[E] {
  parent =>

  /**
   * Apply this Enumerator to an Iteratee
   */
  def apply[A](i: Iteratee[E, A]): Promise[Iteratee[E, A]]

  /**
   * Alias for `apply`
   */
  def |>>[A](i: Iteratee[E, A]): Promise[Iteratee[E, A]] = apply(i)

  /**
   * Sequentially combine this Enumerator with another Enumerator
   */
  def andThen(e: Enumerator[E]): Enumerator[E] = new Enumerator[E] {
    def apply[A](i: Iteratee[E, A]): Promise[Iteratee[E, A]] = parent.apply(i).flatMap(e.apply) //bad implementation, should remove Input.EOF in the end of first
  }

  def interleave[B >: E](other: Enumerator[B]): Enumerator[B] = Enumerator.interleave(this, other)

  def >-[B >: E](other: Enumerator[B]): Enumerator[B] = interleave(other)

  /**
   * Compose this Enumerator with an Enumeratee
   */
  def &>[To](enumeratee: Enumeratee[E, To]): Enumerator[To] = new Enumerator[To] {

    def apply[A](i: Iteratee[To, A]): Promise[Iteratee[To, A]] = {
      val transformed = enumeratee.applyOn(i)
      val xx = parent |>> transformed
      xx.flatMap(_.run)

    }

  }

  def through[To](enumeratee: Enumeratee[E, To]): Enumerator[To] = &>(enumeratee)

  /**
   * Alias for `andThen`
   */
  def >>>(e: Enumerator[E]): Enumerator[E] = andThen(e)

  def map[U](f: E => U): Enumerator[U] = parent &> Enumeratee.map[E](f)

  def mapInput[U](f: Input[E] => Input[U]) = parent &> Enumeratee.mapInput[E](f)

  def flatMap[U](f: E => Enumerator[U]): Enumerator[U] = {
    new Enumerator[U] {
      def apply[A](iteratee: Iteratee[U, A]): Promise[Iteratee[U, A]] = {

        val folder = Iteratee.fold2[E, Iteratee[U, A]](iteratee)((it, e) => f(e)(it).flatMap(newIt => Iteratee.isDoneOrError(newIt).map((newIt, _))))
        parent(folder).flatMap(_.run)
      }
    }
  }

}

object Enumerator {

  def flatten[E](eventuallyEnum: Promise[Enumerator[E]]): Enumerator[E] = new Enumerator[E] {

    def apply[A](it: Iteratee[E, A]): Promise[Iteratee[E, A]] = eventuallyEnum.flatMap(_.apply(it))

  }

  def enumInput[E](e: Input[E]) = new Enumerator[E] {
    def apply[A](i: Iteratee[E, A]): Promise[Iteratee[E, A]] =
      i.fold{ 
        case Step.Cont(k) => Promise.pure(k(e))
        case _ =>  Promise.pure(i)
      }
  }

  def interleave[E1, E2 >: E1](e1: Enumerator[E1], e2: Enumerator[E2]): Enumerator[E2] = new Enumerator[E2] {

    import scala.concurrent.stm._

    def apply[A](it: Iteratee[E2, A]): Promise[Iteratee[E2, A]] = {

      val iter: Ref[Iteratee[E2, A]] = Ref(it)
      val attending: Ref[Option[(Boolean, Boolean)]] = Ref(Some(true, true))
      val result = Promise[Iteratee[E2, A]]()

      def redeemResultIfNotYet(r:Iteratee[E2, A]) = {
        val toRedeem = atomic { implicit transaction =>
          if (attending().isDefined) {
            attending() = None
            //val it = iter()
            Some(r)
          } else None
        }
        toRedeem.foreach(result.redeem(_))
      }

      def iteratee[EE <: E2](f: ((Boolean, Boolean)) => (Boolean, Boolean)): Iteratee[EE, Unit] = {
        def step(in: Input[EE]): Iteratee[EE, Unit] = {

          val p = Promise[Iteratee[E2, A]]()
          val i = iter.single.swap(Iteratee.flatten(p))
          in match {
            case Input.El(_) | Input.Empty =>

              val nextI = i.fold {

                case Step.Cont(k) =>
                  val n = k(in)
                  n.fold {
                    case Step.Cont(kk) =>
                      p.redeem(Cont(kk))
                      Promise.pure(Cont(step))
                    case Step.Done(a,e) => 
                      p.redeem(n)
                      Promise.pure(Done((), Input.Empty: Input[EE]))
                  }
                case _ =>
                  p.redeem(i)
                  Promise.pure(Done((),Input.Empty: Input[EE]))

              }
              Iteratee.flatten(nextI)
            case Input.EOF => {
              if(attending.single.transformAndGet { _.map(f) } == Some((false, false))){
                p.redeem(Iteratee.flatten(i.feed(Input.EOF)))
              } else {
                p.redeem(i)
              }
              Done((), Input.Empty)
            }
          }
        }
        Cont(step)
      }

      val itE1 = iteratee[E1] { case (l, r) => (false, r) }
      val itE2 = iteratee[E2] { case (l, r) => (l, false) }
      val r1 = e1 |>> itE1
      val r2 = e2 |>> itE2
      r1.flatMap(_.pureFold(any => ()))
        .flatMap(_ => r2.flatMap(_.pureFold(any => ()))).extend1 {
        case Redeemed(_) => 
          redeemResultIfNotYet(iter.single())
        case Thrown(e) => result.throwing(e)

      }
      result
    }

  }

  trait Pushee[E] {

    def push(item: E): Boolean

    def close()

  }

  def imperative[E](
    onStart: () => Unit = () => (),
    onComplete: () => Unit = () => (),
    onError: (String, Input[E]) => Unit = (_: String, _: Input[E]) => ()): PushEnumerator[E] = new PushEnumerator[E](onStart, onComplete, onError)



  def pushee[E](
    onStart: Pushee[E] => Unit,
    onComplete: () => Unit = () => (),
    onError: (String, Input[E]) => Unit = (_: String, _: Input[E]) => ()) = new Enumerator[E] {

    def apply[A](it: Iteratee[E, A]): Promise[Iteratee[E, A]] = {
      var iteratee: Iteratee[E, A] = it
      var promise: Promise[Iteratee[E, A]] with Redeemable[Iteratee[E, A]] = new STMPromise[Iteratee[E, A]]()

      val pushee = new Pushee[E] {
        def close() {
          if (iteratee != null) {
            iteratee.feed(Input.EOF).map(result => promise.redeem(result))
            iteratee = null
            promise = null
          }
        }
        def push(item: E): Boolean = {
          if (iteratee != null) {
            iteratee = iteratee.pureFlatFold[E, A] {

              case Step.Done(a, in) => {
                onComplete()
                Done(a, in)
              }

              case Step.Cont(k) => {
                val next = k(Input.El(item))
                next.pureFlatFold {
                  case Step.Done(a, in) => {
                    onComplete()
                    next
                  }
                  case _ => next
                }
              }

              case Step.Error(e, in) => {
                onError(e, in)
                Error(e, in)
              }
            }
            true
          } else {
            false
          }
        }
      }
      onStart(pushee)
      promise
    }

  }

  import scalax.io.JavaConverters._

  def unfoldM[S,E](s:S)(f: S => Promise[Option[(S,E)]] ): Enumerator[E] = checkContinue1(s)(new TreatCont1[E,S]{

    def apply[A](loop: (Iteratee[E,A],S) => Promise[Iteratee[E,A]], s:S, k: Input[E] => Iteratee[E,A]):Promise[Iteratee[E,A]] = f(s).flatMap {
      case Some((newS,e)) => loop(k(Input.El(e)),newS)
      case None => Promise.pure(Cont(k))
    }
  })

  def unfold[S,E](s:S)(f: S => Option[(S,E)] ): Enumerator[E] = checkContinue1(s)(new TreatCont1[E,S]{

    def apply[A](loop: (Iteratee[E,A],S) => Promise[Iteratee[E,A]], s:S, k: Input[E] => Iteratee[E,A]):Promise[Iteratee[E,A]] = f(s) match {
      case Some((s,e)) => loop(k(Input.El(e)),s)
      case None => Promise.pure(Cont(k))
    }
  })

  def repeat[E](e: => E): Enumerator[E] = checkContinue0( new TreatCont0[E]{

    def apply[A](loop: Iteratee[E,A] => Promise[Iteratee[E,A]], k: Input[E] => Iteratee[E,A]) = loop(k(Input.El(e)))

  })

  def repeatM[E](e: => Promise[E]): Enumerator[E] = checkContinue0( new TreatCont0[E]{

    def apply[A](loop: Iteratee[E,A] => Promise[Iteratee[E,A]], k: Input[E] => Iteratee[E,A]) = e.flatMap(ee => loop(k(Input.El(ee))))

  })

  def generateM[E](e: => Promise[Option[E]]): Enumerator[E] = checkContinue0( new TreatCont0[E] {

    def apply[A](loop: Iteratee[E,A] => Promise[Iteratee[E,A]], k: Input[E] => Iteratee[E,A]) = e.flatMap {
      case Some(e) => loop(k(Input.El(e)))
      case None => Promise.pure(Cont(k))
    }
  })

  trait TreatCont0[E]{

    def apply[A](loop: Iteratee[E,A] => Promise[Iteratee[E,A]], k: Input[E] => Iteratee[E,A]):Promise[Iteratee[E,A]]

  }

  def checkContinue0[E](inner:TreatCont0[E]) = new Enumerator[E] {

    def apply[A](it: Iteratee[E, A]): Promise[Iteratee[E, A]] = {

      def step(it: Iteratee[E, A]): Promise[Iteratee[E,A]] = it.fold {
          case Step.Done(a, e) => Promise.pure(Done(a,e))
          case Step.Cont(k) => inner[A](step,k)
          case Step.Error(msg, e) => Promise.pure(Error(msg,e))
      }

      step(it)
    }
  }

  trait TreatCont1[E,S]{

    def apply[A](loop: (Iteratee[E,A],S) => Promise[Iteratee[E,A]], s:S, k: Input[E] => Iteratee[E,A]):Promise[Iteratee[E,A]]

  }

  def checkContinue1[E,S](s:S)(inner:TreatCont1[E,S]) = new Enumerator[E] {

    def apply[A](it: Iteratee[E, A]): Promise[Iteratee[E, A]] = {

      def step(it: Iteratee[E, A], state:S): Promise[Iteratee[E,A]] = it.fold{
          case Step.Done(a, e) => Promise.pure(Done(a,e))
          case Step.Cont(k) => inner[A](step,state,k)
          case Step.Error(msg, e) => Promise.pure(Error(msg,e))
      }
      step(it,s)
    }

  }

  def fromCallback1[E](retriever: Boolean => Promise[Option[E]],
    onComplete: () => Unit = () => (),
    onError: (String, Input[E]) => Unit = (_: String, _: Input[E]) => ()) = new Enumerator[E] {
    def apply[A](it: Iteratee[E, A]): Promise[Iteratee[E, A]] = {

      var iterateeP = Promise[Iteratee[E, A]]()

      def step(it: Iteratee[E, A], initial: Boolean = false) {

        val next = it.fold {
          case Step.Cont(k) => {
            retriever(initial).map {
              case None => {
                val remainingIteratee = k(Input.EOF)
                iterateeP.redeem(remainingIteratee)
                None
              }
              case Some(read) => {
                val nextIteratee = k(Input.El(read))
                Some(nextIteratee)
              }
            }
          }
          case _ => { iterateeP.redeem(it); Promise.pure(None) }
        }

        next.extend1 {
          case Redeemed(Some(i)) => step(i)

          case Redeemed(None) => onComplete()
          case Thrown(e) =>
            iterateeP.throwing(e)
        }
      }
      step(it, true)
      iterateeP
    }
  }

  def fromCallback[E](retriever: () => Promise[Option[E]],
    onComplete: () => Unit = () => (),
    onError: (String, Input[E]) => Unit = (_: String, _: Input[E]) => ()) = new Enumerator[E] {
    def apply[A](it: Iteratee[E, A]): Promise[Iteratee[E, A]] = {

      var iterateeP = Promise[Iteratee[E, A]]()

      def step(it: Iteratee[E, A]) {

        val next = it.fold{
          case Step.Cont(k) => {
            retriever().map {
              case None => {
                val remainingIteratee = k(Input.EOF)
                iterateeP.redeem(remainingIteratee)
                None
              }
              case Some(read) => {
                val nextIteratee = k(Input.El(read))
                Some(nextIteratee)
              }
            }
          }
          case _ => { iterateeP.redeem(it); Promise.pure(None) }
        }

        next.extend1 {
          case Redeemed(Some(i)) => step(i)
          case Thrown(e) =>
            iterateeP.throwing(e)
          case _ => onComplete()
        }

      }

      step(it)
      iterateeP
    }
  }

  def fromStream(input: java.io.InputStream, chunkSize: Int = 1024 * 8) = {
    fromCallback(() => {
      val buffer = new Array[Byte](chunkSize)
      val chunk = input.read(buffer) match {
        case -1 => None
        case read =>
          val input = new Array[Byte](read)
          System.arraycopy(buffer, 0, input, 0, read)
          Some(input)
      }
      Promise.pure(chunk)
    }, input.close)
  }

  def fromFile(file: java.io.File, chunkSize: Int = 1024 * 8): Enumerator[Array[Byte]] = {
    fromStream(new java.io.FileInputStream(file), chunkSize)
  }

  def eof[A] = enumInput[A](Input.EOF)

  /**
   * Create an Enumerator from a set of values
   *
   * Example:
   * {{{
   *   val enumerator: Enumerator[String] = Enumerator("kiki", "foo", "bar")
   * }}}
   */
  def apply[E](in: E*): Enumerator[E] = new Enumerator[E] {

    def apply[A](i: Iteratee[E, A]): Promise[Iteratee[E, A]] = enumerate(in, i)

  }

  private def enumerate[E, A]: (Seq[E], Iteratee[E, A]) => Promise[Iteratee[E, A]] = { (l, i) =>
    l.foldLeft(Promise.pure(i))((i, e) =>
      i.flatMap(it => it.pureFold{ 
        case Step.Cont(k) => k(Input.El(e))
        case _ => it
      }))
  }
}

class PushEnumerator[E] private[iteratee] (
    onStart: () => Unit = () => (),
    onComplete: () => Unit = () => (),
    onError: (String, Input[E]) => Unit = (_: String, _: Input[E]) => ()) extends Enumerator[E] with Enumerator.Pushee[E] {

  var iteratee: Iteratee[E, _] = _
  var promise: Promise[Iteratee[E, _]] with Redeemable[Iteratee[E, _]] = _

  def apply[A](it: Iteratee[E, A]): Promise[Iteratee[E, A]] = {
    onStart()
    iteratee = it.asInstanceOf[Iteratee[E, _]]
    val newPromise = new STMPromise[Iteratee[E, A]]()
    promise = newPromise.asInstanceOf[Promise[Iteratee[E, _]] with Redeemable[Iteratee[E, _]]]
    newPromise
  }

  def close() {
    if (iteratee != null) {
      iteratee.feed(Input.EOF).map(result => promise.redeem(result))
      iteratee = null
      promise = null
    }
  }

  def push(item: E): Boolean = {
    if (iteratee != null) {
      iteratee = iteratee.pureFlatFold[E, Any] {

        case Step.Done(a, in) => {
          onComplete()
          Done(a, in)
        }

        case Step.Cont(k) => {
          val next = k(Input.El(item))
          next.pureFlatFold {
            case Step.Done(a, in) => {
              onComplete()
              next
            }
            case _ => next
          }
        }

        case Step.Error(e, in) => {
          onError(e, in)
          Error(e, in)
        }
      }
      true
    } else {
      false
    }
  }

}