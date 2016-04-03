package flow.akka

import akka.actor.Status.Success
import akka.actor._
import akka.actor.ActorSystem
import akka.util.Timeout

import scala.annotation.tailrec
import scala.concurrent.Await
import akka.pattern.ask

import scala.concurrent.duration._
import akka.event.LoggingReceive
import dag.DAG
import util.ParamTuple

import scala.util.{Failure, Try}

case class AkkaDAG(graph: DAG, functions: Map[String, Function[Any, Any]], sys : Option[ActorSystem] = None) {
  val dagSpecs = for {
    node <- graph.nodes
  } yield DagSpec(node.label, node.getParentLabels.toList)

  DagManager(dagSpecs, functions, sys)
}

case class DagSpec(id : String, precursors : List[String], delay : Long = 0L, fail : Boolean = false)

case class DagManager(input : List[DagSpec], functions: Map[String, Function[Any, Any]], sys : Option[ActorSystem] = None) {

  println("start..")
  assert(input.map(_.id).distinct.size == input.size, "Duplicate entries in the task list")
  val system = sys.getOrElse( ActorSystem("dag") )
  implicit val timeout = Timeout(5 seconds)
  val monitor = system.actorOf(Props[DagStatus],"monitor")
  println("monitor..")

  val dagDeps = input.map( node => node.precursors -> node.id )

  val dagRealized = input.map( node => {
    node.id -> system.actorOf(
      Props(classOf[DagNode], node.id, node.precursors, functions(node.id), monitor, node.delay, node.fail),
      node.id + "_executor")
  }).toMap

  val terminal_nodes = dagRealized.flatMap {
    case (id, node) => {
      dagDeps.filter(_._1.contains(id)).map(_._2) match {
        case depsIds: List[String] if depsIds.nonEmpty => {
          // this is where the node has dependencies
          node ! ConfigDeps(dagRealized.filter(n => depsIds.contains(n._1)).values.toList) // find the dependencies of each node
          None
        }
        case _ => {
          // these are the DAG end nodes
          node ! ConfigDeps(List[ActorRef]())
          Some(node)
        }
      }
    }
  }

  // find the start nodes and kick them
  val start_nodes = input.filter(_.precursors.isEmpty).map(_.id)
  dagRealized.foreach{ case (id,node) => {
    if(start_nodes.contains(id)){
      node ! Kick("$starter", ())
    }
  }}

  var completes = List[String]()

  @tailrec
  final def checkGraph() : List[String] = {
    val new_completes = Await.result( ask(monitor, DagCompletesQuery).mapTo[List[String]], 5 seconds )
    val failures = Await.result( ask(monitor, DagFailuresQuery).mapTo[List[String]], 5 seconds )
    if (failures.nonEmpty){
      println(s"Fail at $failures \n\t aborting the graph!")
      dagRealized.foreach{ case (id,node) => node ! PoisonPill }
    }

    if(new_completes.size > completes.size){
      println(s"${new_completes.diff(completes).head} reported complete - now total complete $new_completes")
      completes = new_completes
    }

    if(completes.size == input.size || failures.nonEmpty) completes
    else checkGraph()
  }

  println("run completed with " + checkGraph())
  monitor ! PoisonPill

  system.shutdown()

  sys.getOrElse({
    system.awaitTermination()
  })

}

//messages
case class ConfigDeps( deps : List[ActorRef] )
case class Kick(from: String, result: Any)
case class Cancel()
case class ReportStart(id : String, dag : ActorRef)
case class ReportDone(id : String, dag : ActorRef)
case class ReportError(id : String, dag : ActorRef, cause : Throwable)
case class DagCompletesQuery()
case class DagFailuresQuery()

//actors
class DagNode(id: String, pre : List[String], func: Function[Any, Any], monitor : ActorRef, delay : Long, fail : Boolean)
  extends Actor with akka.actor.ActorLogging  {
  import context._
  var post = List[ActorRef]()
  var kicks = List[String]()
  var inputs = List[Any]()

  override def receive : Receive = LoggingReceive {
    case ConfigDeps(deps) => {
      post = deps
      println(s"${self.path.name} configured - becoming active")
      become(active)
    }
  }

  def active : Receive = LoggingReceive {
    case Kick(from, input) => {
      kicks = from :: kicks
      inputs = inputs :+ input
      println(s"${self.path.name} kicked from $from - total kicks: \n\t${kicks} out of $pre " +
        s"so waiting for ${pre.diff(kicks)} kicks")

      if(pre.isEmpty) {  // this is a starter node
        scheduleTask()
      } else {
        pre.diff(kicks).size match {
          case 0 => {
            scheduleTask()
          }
          case 1 => monitor ! ReportStart(id, self)
          case _ => //between kick 1 and kicks complete - keep waiting
        }
      }
    }
    case Success(res) => {
      monitor ! ReportDone(id, self)
      post.foreach(_ ! Kick(id, res))
      self ! PoisonPill
    }
    case Failure(ex) => {
      monitor ! ReportError(id,self,ex)
      self ! PoisonPill
    }
  }

  /* We only need to schedule this to simulate a delay - normally we would just do the work
   */
  def scheduleTask() = system.scheduler.scheduleOnce(delay milliseconds) {
    if(fail) {
      println(s"Simulated failure at ${self.path.name}")
      self ! Failure(new Exception("Born to fail"))
    } else {
      val result = ParamTuple(func)(inputs)
      println(s"$id -- inputs: $inputs, output: $result")
      self ! Success(result)
    }
  }

}

class DagStatus extends Actor with akka.actor.ActorLogging {

  var completes = Vector[String]()
  var failures = Vector[String]()
  var working = Vector[ActorRef]()

  override def receive : Receive = LoggingReceive {
    case ReportDone(id, ref) => {
      completes = completes :+ id
      working = working.filterNot(_ == ref)
    }
    case ReportError(id, ref, cause) => {
      failures = failures :+ id
      working = working.filterNot(_ == ref)
    }
    case ReportStart(id, ref) => {
      working = working :+ ref
    }
    case DagCompletesQuery => {
      sender ! completes.toList
    }
    case DagFailuresQuery => {
      sender ! failures.toList
    }
  }
}



