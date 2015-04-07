package com.hanh

import scala.concurrent.duration._
import akka.actor.{Actor, Props, ActorSystem}
import akka.testkit.{TestProbe, ImplicitSender, TestKit, TestActorRef}
import org.scalatest.{WordSpecLike, Matchers, FunSuite}

class ActorSuite(system: ActorSystem) extends TestKit(system) with ImplicitSender
  with WordSpecLike with Matchers {
  implicit val s = system
  def this() = this(ActorSystem())

  "A Refresh actor" must {
    "accept messages" in {
      val refresh = system.actorOf(Props(new Refresh))
      refresh ! Message(10, 0, Array.empty)
      refresh ! Message(11, 0, Array.empty)
    }

    "return a range of messages between two RefreshComplete" in {
      val probe = TestProbe()
      val parent = system.actorOf(Props(new Actor {
        val refresh = context.actorOf(Props(new Refresh))
        def receive = {
          case x if sender == refresh => probe.ref forward x
          case x => refresh forward x
        }
      }))

      parent ! Message(10, 0, Array.empty)
      parent ! Message(11, 0, Array.empty)
      parent ! Message(12, 203, MessageBase.encode(RefreshComplete(11)))
      val m13 = Message(13, 0, Array.empty)
      val m14 = Message(14, 0, Array.empty)
      val m15 = Message(15, 0, Array.empty)
      parent ! m15 // send them out of order
      parent ! m14
      parent ! m13
      parent ! Message(16, 203, MessageBase.encode(RefreshComplete(15)))
      parent ! Message(17, 0, Array.empty)
      parent ! Message(18, 0, Array.empty)

      probe.expectMsg[RefreshMessages](RefreshMessages(15, List(m13, m14, m15))) // should get them in order
    }
  }

  def createProbeForRealtime() = {
    val probe = TestProbe()
    val parent = system.actorOf(Props(new Actor {
      val child = context.actorOf(Props(new Realtime))
      def receive = {
        case x if sender == child => probe.ref forward x
        case x => child forward x
      }
    }))
    (parent, probe)
  }

  "A Realtime actor" must {
    "buffer messages until started" in {
      val (parent, probe) = createProbeForRealtime()

      parent ! Message(10, 0, Array.empty)
      probe.expectNoMsg(100.millisecond)
    }

    "send messages after being started that are above the starting seqNo" in {
      val (parent, probe) = createProbeForRealtime()

      val m13 = Message(13, 0, Array.empty)
      parent ! RefreshMessages(15, List(m13))
      val m14 = Message(14, 0, Array.empty)
      parent ! m14
      val m15 = Message(15, 0, Array.empty)
      parent ! m15
      val m16 = Message(16, 0, Array.empty)
      parent ! m16
      probe.expectMsg[Message](m16)
    }
  }

  "A OMD actor" must {
    "wait for refresh then send real time" in {
      val probe = new TestProbe(system) {
        def expectMsgSeqNo(seqNo: Int) = {
          expectMsgPF() {
            case m: Message => m.seqNo == seqNo
          }
        }
      }
      val omd = TestActorRef(new OMD(probe.ref))
      val rtMsgs = for (i <- 10 until 15) yield Message(i, 0, Array.empty)
      rtMsgs foreach (omd.underlyingActor.realtime ! _)
      omd.underlyingActor.refresh ! Message(12, 203, MessageBase.encode(RefreshComplete(11)))
      val m13 = Message(13, 0, Array.empty)
      omd.underlyingActor.refresh ! m13
      omd.underlyingActor.refresh ! Message(14, 203, MessageBase.encode(RefreshComplete(13)))

      probe.expectMsgSeqNo(13)
      probe.expectMsgSeqNo(14)
      probe.expectNoMsg(500.millisecond)
    }
  }
}
