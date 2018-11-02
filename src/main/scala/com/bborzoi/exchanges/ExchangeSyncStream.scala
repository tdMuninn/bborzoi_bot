package com.bborzoi.exchanges

import akka._
import akka.actor._
import akka.stream._
import akka.stream.scaladsl._
import akka.stream.stage.{GraphStage, GraphStageLogic, InHandler, OutHandler}
import com.typesafe.scalalogging.Logger
import org.slf4j.LoggerFactory

import scala.collection.immutable.Iterable
import scala.concurrent._
import scala.util.Random

class Duplicator[I](message: String) extends GraphStage[FlowShape[I, I]] {

  val in = Inlet[I]("Duplicator.in")
  val out: Outlet[I] = Outlet[I]("Duplicator.out")

  val shape = FlowShape.of(in, out)

  override def createLogic(inheritedAttributes: Attributes): GraphStageLogic =
    new GraphStageLogic(shape) {

      setHandler(in, new InHandler {
        override def onPush(): Unit = {
          val elem = grab(in)
          println(s"Duplicator ${elem} ${message}")
          val l = Iterable(elem, elem)
          emitMultiple(out, l)
        }
      })
      setHandler(out, new OutHandler {
        override def onPull(): Unit = {
          pull(in)
        }
      })
    }
}


case class Apple(bad: Boolean)


object ExchangeSyncStream {
  def run(): Unit = {

    val logger = Logger(LoggerFactory.getLogger("ExchangeSyncStream"))
    logger.debug(s"Prepare and run!")

    implicit val system: ActorSystem = ActorSystem()
    implicit val ec: ExecutionContextExecutor = system.dispatcher
    implicit val materializer: ActorMaterializer = ActorMaterializer()


    val apples = Source(Vector.fill(10) {
      Apple(Random.nextBoolean())
    }).take(4)

    val duplicator = new Duplicator("ololo")

    val goodApples = Sink.foreach[Apple](value => println(s"good apple ${value}"))
    val badApples = Sink.foreach[Apple](value => println(s"bad apple ${value}"))


    val killSwitch = KillSwitches.single[Apple]

    val systemGraph: RunnableGraph[(UniqueKillSwitch, NotUsed, Future[Done], Future[Done])] = RunnableGraph.fromGraph(
      GraphDSL.create(killSwitch, apples, badApples, goodApples)((_, _, _, _)) {
        implicit builder =>
          (switch, source, badStream, goodStream) =>
            import GraphDSL.Implicits._

            val broadcast = builder.add(Partition[Apple](2, apple => if (apple.bad) 1 else 0))
            val multiply = builder.add(new Duplicator[Apple]("ololo2"))

            source ~> switch ~> broadcast.in
            broadcast.out(0) ~> badStream
            broadcast.out(1) ~> multiply ~> goodStream

            ClosedShape
      }
    )

    val status = systemGraph.run()
    Future.sequence(List(status._3, status._4))
      .onComplete {
        _ => system.terminate()
      }

    //    val timedGraph: RunnableGraph[Future[FiniteDuration]] = systemGraph.mapMaterializedValue(
    //      status => {
    //        val start = System.nanoTime()
    //        Future.sequence(List(status._3, status._4)) map {
    //          _ =>
    //            FiniteDuration(System.nanoTime() - start, TimeUnit.NANOSECONDS)
    //        }
    //      }
    //    )
    //    timedGraph.run().onComplete {
    //      duration ⇒
    //        println(s"Elapsed time: $duration")
    //        system.terminate()
    //    }

  }

}

