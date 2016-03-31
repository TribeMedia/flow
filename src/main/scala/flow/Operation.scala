package flow

import dag.{DAG, Node}
import util.ParamTuple

import scala.collection.mutable

sealed trait Operation[A] extends (() => A) {
  def map[B](f: A ⇒ B): Operation[B] = Operation(f(apply()))
  def flatMap[B](f: A => Operation[B]): Operation[B] = Operation(f(apply()).apply()) //f(apply())
  def -->[B] (t: TransformerU[A, B]): Operation[B] = t(apply())
}

object OperationImplicits {
  implicit def Function0ToOperation[A] (f: => A) = Operation(f)
  implicit def Function1ToTransformer[In, Out] (f: In => Out) = Transformer(f)
}

trait Named {
  def label: String = this.getClass().getName()
}

trait StatsCollector[A] extends Operation[A] { this: Named =>
  import StatsCollector._
  abstract override def apply(): A = {
    val (res, duration) = time(super.apply())
    println("name = " + label + ", result = " + res + ", duration = " + duration)
    res
  }
}

object StatsCollector {
  val stats = mutable.HashMap[String, Any]()

  def time[T](thunk: => T): (T, Long) = {
    val t1 = System.currentTimeMillis
    val t = thunk
    val t2 = System.currentTimeMillis
    (t, t2 - t1)
  }
}

trait LazyOperation[A] extends Operation[A] {
  lazy val value = super.apply
  abstract override def apply(): A = value
}

class OperationImpl[A](f: => A) extends Operation[A] {
  override def apply() = f
}

object Operation {
  def apply[A](f: => A, beLazy: Boolean = true): Operation[A] = beLazy match {
    case true => new OperationImpl[A](f) with LazyOperation[A] with StatsCollector[A] with Named
    case false => new OperationImpl[A](f) with StatsCollector[A] with Named
  }

  def sequence[A](list: List[Operation[A]]): Operation[List[A]] = list match {
    case Nil => Operation({Nil})
    case x :: xs => x.flatMap(h => sequence(xs).map(t => h :: t))
  }

  def map2[A,B,C](ma: Operation[A], mb: Operation[B])(f: (A, B) => C): Operation[C] =
    ma.flatMap(a => mb.map(b => f(a, b)))

//  def sequence[A](lma: List[Operation[A]]): Operation[List[A]] =
//    lma.foldRight(Operation(List[A]()))((ma, mla) => map2(ma, mla)(_ :: _))
}

trait TransformerU[In, Out] {
  def f: In => Out
  def apply(in: In) = Operation[Out] { f(in) }
}

case class Transformer[In, Out](f: In => Out) extends TransformerU[In, Out]

object OperationBuilder {
  def apply(graph: DAG, values: Map[String, Any],
            functions: Map[String, Function[Any, Any]]): Map[String, Operation[_]] = {

    val ops = collection.mutable.Map[String, Operation[Any]]()

    def build(node: Node): Unit = {
      node.getParents foreach (dep => build(dep))
      val label = node.label

      if (!ops.contains(label)) {

        if (node.isRoot) {
          val value = values(label)
          ops(label) = Operation(value)
        }
        else {
          val deps = node.getParentLabels collect ops
          ops(label) = deps.length match {
            case 1 => deps.head.map(functions(label))
            case 2 => Operation.map2(deps.head, deps.last)((h, l) => functions(label)(h, l))
            case _ => Operation.sequence(deps.toList).map(ParamTuple(functions(label)))
          }
        }
      }
    }

    graph.getLeaves foreach( leaf => build(leaf))

    ops.toMap
  }
}

