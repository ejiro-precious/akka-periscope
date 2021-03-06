package io.scalac.periscope.akka.deadletters

import akka.actor.{ Actor, ActorSystem, PoisonPill, Props }
import akka.dispatch.{ BoundedMessageQueueSemantics, RequiresMessageQueue }
import akka.pattern.ask
import akka.testkit.{ ImplicitSender, TestKit }
import akka.util.Timeout
import io.scalac.periscope.akka._
import io.scalac.periscope.akka.deadletters.AbstractDeadLettersDataCollector._
import org.scalatest.concurrent.{ Eventually, ScalaFutures }
import org.scalatest.flatspec.AnyFlatSpecLike
import org.scalatest.matchers.should.Matchers
import org.scalatest.time.{ Milliseconds, Span }
import org.scalatest.{ BeforeAndAfterAll, Inside }

import scala.concurrent.duration._

class DeadLettersDataCollectorSpec
    extends TestKit(ActorSystem("DeadLettersDataCollectorSpec"))
    with Matchers
    with ImplicitSender
    with AnyFlatSpecLike
    with ScalaFutures
    with Inside
    with Eventually
    with BeforeAndAfterAll {

  override implicit def patienceConfig: PatienceConfig = PatienceConfig(scaled(Span(600, Milliseconds)))
  private val withinTime: FiniteDuration               = 20.seconds
  private implicit val timeout: Timeout                = Timeout(withinTime)

  "DeadLettersDataCollector" should "collect dead letters" in {

    val a         = system.actorOf(Props(new ActorA), "a1")
    val collector = system.actorOf(Props(new DeadLettersDataCollector(10)), "collector1")

    subscribe(system, collector)

    a ! KnownMessage("alive")
    a ! PoisonPill
    a ! KnownMessage("dead")

    eventually {
      val window   = (collector ? CalculateForWindow(withinTime.toMillis * 2)).mapTo[WindowSnapshot].futureValue
      val snapshot = (collector ? GetSnapshot).mapTo[Snapshot].futureValue
      snapshot.unhandled should be(empty)
      snapshot.dropped should be(empty)

      window.deadLetters.count shouldBe 1
      window.unhandled.count shouldBe 0
      window.dropped.count shouldBe 0
    }

    unsubscribe(system, collector)
    system.stop(a)
    system.stop(collector)
  }

  it should "collect unhandled messages" in {
    val a         = system.actorOf(Props(new ActorA), "a2")
    val collector = system.actorOf(Props(new DeadLettersDataCollector(10)), "collector2")
    subscribe(system, collector)

    a ! KnownMessage("alive")
    a ! UnknownMessage("am I?")
    a ! UnknownMessage("Something is wrong")
    a ! UnknownMessage("Luke, I'm your father!")

    eventually {
      val window   = (collector ? CalculateForWindow(withinTime.toMillis * 2)).mapTo[WindowSnapshot].futureValue
      val snapshot = (collector ? GetSnapshot).mapTo[Snapshot].futureValue
      inside(snapshot.unhandled) {
        case Vector(m1, m2, m3) =>
          // reverse order - latest first
          m1.value.message shouldBe UnknownMessage("Luke, I'm your father!")
          m2.value.message shouldBe UnknownMessage("Something is wrong")
          m3.value.message shouldBe UnknownMessage("am I?")
      }
      snapshot.deadLetters should be(empty)
      snapshot.dropped should be(empty)

      window.deadLetters.count shouldBe 0
      window.unhandled.count shouldBe 3
      window.dropped.count shouldBe 0
    }

    unsubscribe(system, collector)
    system.stop(a)
    system.stop(collector)
  }

  it should "not keep more messages than required" in {
    val a         = system.actorOf(Props(new ActorA), "a3")
    val collector = system.actorOf(Props(new DeadLettersDataCollector(5)), "collector3")
    subscribe(system, collector)

    a ! UnknownMessage("1")
    a ! UnknownMessage("2")
    a ! UnknownMessage("3")
    a ! UnknownMessage("4")
    a ! UnknownMessage("5")
    a ! UnknownMessage("6")
    a ! UnknownMessage("7")

    eventually {
      val window = (collector ? CalculateForWindow(withinTime.toMillis * 2)).mapTo[WindowSnapshot].futureValue

      val snapshot = (collector ? GetSnapshot).mapTo[Snapshot].futureValue

      snapshot.unhandled.map(_.value.message.asInstanceOf[UnknownMessage].text) shouldBe Vector("7", "6", "5", "4", "3")
      snapshot.deadLetters should be(empty)
      snapshot.dropped should be(empty)

      window.deadLetters.count shouldBe 0
      window.unhandled.count shouldBe 5
      window.dropped.count shouldBe 0
    }

    unsubscribe(system, collector)
    system.stop(a)
    system.stop(collector)
  }

  it should "mark window calculations as estimates if window is not full" in {
    val a         = system.actorOf(Props(new ActorA), "a4")
    val collector = system.actorOf(Props(new DeadLettersDataCollector(10)), "collector4")
    subscribe(system, collector)

    a ! UnknownMessage("a1")
    a ! UnknownMessage("a2")
    a ! UnknownMessage("a3")

    eventually {
      val window = (collector ? CalculateForWindow(withinTime.toMillis * 2)).mapTo[WindowSnapshot].futureValue

      window.unhandled.count shouldBe 3
      window.unhandled.isMinimumEstimate shouldBe true
    }

    unsubscribe(system, collector)
    system.stop(a)
    system.stop(collector)
  }

  it should "mark window calculations as precise if window is fully cached" in {
    val a         = system.actorOf(Props(new ActorA), "a5")
    val collector = system.actorOf(Props(new DeadLettersDataCollector(10)), "collector5")
    subscribe(system, collector)

    a ! UnknownMessage("1")
    Thread.sleep(500)
    a ! UnknownMessage("2")
    Thread.sleep(500)
    a ! UnknownMessage("3")

    eventually {
      val window = (collector ? CalculateForWindow(300)).mapTo[WindowSnapshot].futureValue
      window.unhandled.count should be > 0
      window.unhandled.isMinimumEstimate shouldBe false
    }

    subscribe(system, collector)
    system.stop(a)
    system.stop(collector)
  }

  override def afterAll: Unit =
    TestKit.shutdownActorSystem(system)

}

class BoundedActor extends Actor with RequiresMessageQueue[BoundedMessageQueueSemantics] {
  def receive: Receive = {
    case _ =>
      Thread.sleep(3000)
  }
}
