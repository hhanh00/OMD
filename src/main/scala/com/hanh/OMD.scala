package com.hanh

import java.io.{ByteArrayOutputStream, ByteArrayInputStream}
import java.net._
import java.nio.ByteBuffer
import java.nio.channels.DatagramChannel

import scala.collection.JavaConversions._

import akka.actor.{ActorRef, Props, Actor}
import com.google.common.io.{LittleEndianDataOutputStream, LittleEndianDataInputStream}

import scala.collection.mutable.ListBuffer

case class Message(seqNo: Int, tpe: Int, message: Array[Byte])

trait MessageBase {
  def write(os: LittleEndianDataOutputStream)
}
case class RefreshComplete(seqNo: Int) extends MessageBase {
  def write(os: LittleEndianDataOutputStream) = {
    os.writeInt(seqNo)
  }
}
object RefreshComplete {
  def read(is: LittleEndianDataInputStream) = {
    val seqNo = is.readInt()
    RefreshComplete(seqNo)
  }
}
case class OtherMessage(bytes: Array[Byte]) extends MessageBase {
  def write(os: LittleEndianDataOutputStream) = {
    os.write(bytes)
  }
}

object MessageBase {
  def decode(tpe: Int, bytes: Array[Byte]): MessageBase = {
    val is = new LittleEndianDataInputStream(new ByteArrayInputStream(bytes))
    tpe match {
      case 203 => RefreshComplete.read(is)
      case _ => OtherMessage(bytes)
    }
  }
  def encode(m: MessageBase) = {
    val ba = new ByteArrayOutputStream()
    val os = new LittleEndianDataOutputStream(ba)
    m.write(os)
    ba.toByteArray
  }
}

object RingBuffer {
  val maxSize = 1024
}

class RingBuffer {
  import RingBuffer._
  val buffer: Array[Option[Message]] = Iterator.continually(None).take(maxSize).toArray
  var currentIndex: Option[Int] = None

  private def seqNoToIndex(seqNo: Int) = seqNo % maxSize

  private def get(seqNo: Int) = buffer(seqNoToIndex(seqNo))
  private def set(message: Message) = (buffer(seqNoToIndex(message.seqNo)) = Some(message))

  def isEmpty = buffer.forall(_.isEmpty)
  def add(message: Message): Unit = {
    if (currentIndex.isEmpty || currentIndex.get < message.seqNo) {
      val old = get(message.seqNo)
      set(
        old.map(m =>
          if (m.seqNo != message.seqNo) throw new RuntimeException("Element in use") else m
        ).getOrElse(message))
    }
  }

  private def next(): Option[Message] = {
    currentIndex match {
      case None =>
        val c = buffer.flatMap(_.map(_.seqNo)).reduceOption(_ min _)
        c flatMap { c =>
          currentIndex = Some(c-1)
          next()
        }
      case Some(idx) =>
        val n = buffer(seqNoToIndex(idx+1))
        if (n.isDefined) {
          currentIndex = currentIndex.map(_ + 1)
          buffer(seqNoToIndex(idx+1)) = None
        }
        n
    }
  }
  def takeAvailable() = Iterator.continually(next()).takeWhile(_.isDefined).take(maxSize).map(_.get).toList
}

case class RefreshMessages(seqNo: Int, refresh: List[Message])

class Refresh extends Actor {
  val buffer = new RingBuffer
  val data = new ListBuffer[Message]
  var generation = 0

  private def decodeRefreshComplete(m: Message): Int = {
    val refreshComplete = MessageBase.decode(m.tpe, m.message)
    refreshComplete match {
      case rc: RefreshComplete => rc.seqNo
    }
  }

  def receive = {
    case m: Message =>
      buffer.add(m)
      val newMessages = buffer.takeAvailable()
      val (current, next) = newMessages.span(m => m.tpe != 203)
      data ++= current
      next.headOption.foreach { h =>
        val seqNo = decodeRefreshComplete(h)
        generation += 1
        val refresh = data.toList
        data.clear()
        if (generation > 1) {
          context.parent ! RefreshMessages(seqNo, refresh)
        }
        data ++= next.tail
      }
  }
}

class Realtime extends Actor {
  val buffer = new RingBuffer
  def receive = {
    case m: Message =>
      buffer.add(m)

    case RefreshMessages(seqNo, _) =>
      emitFrom(seqNo)
      context become {
        case m: Message =>
          buffer.add(m)
          emitFrom(seqNo)
      }
  }

  private def emitFrom(seqNo: Int) = {
    val rt = buffer.takeAvailable().filter(_.seqNo > seqNo)
    rt foreach (context.parent ! _)
  }
}

class OMD(listener: ActorRef) extends Actor {
  val refresh = context.actorOf(Props(new Refresh))
  val realtime = context.actorOf(Props(new Realtime))

  private def processMessage(message: Message): Unit = listener ! message

  def receive = {
    case refreshComplete @ RefreshMessages(_, refreshMessages) =>
      context stop refresh
      realtime ! refreshComplete
      refreshMessages foreach (processMessage(_))

    case m: Message => processMessage(m)
  }
}

class UDPSender {
  val channel = DatagramChannel.open()
  channel.socket.bind(new InetSocketAddress(9300))

  def send() = {
    val buf = ByteBuffer.allocate(80)
    buf.put("Hello".getBytes())
    buf.flip()
    channel.connect(new InetSocketAddress("localhost", 9300))
    val cSent = channel.write(buf)
    channel.receive(buf)
    buf.flip()
    val ba: Array[Byte] = new Array(5)
    buf.get(ba)
    val s = new String(ba)
    s
  }
}

class MulticastActor {
//  val x = NetworkInterface.getNetworkInterfaces().toList
  val ni = NetworkInterface.getByName("eth3")
  val group = InetAddress.getByName("127.0.0.0")
  val dc = DatagramChannel.open(StandardProtocolFamily.INET)
    .setOption[java.lang.Boolean](StandardSocketOptions.SO_REUSEADDR, true)
    .bind(new InetSocketAddress(9300))
    .setOption[java.lang.Boolean](StandardSocketOptions.IP_MULTICAST_LOOP, true)

  val key = dc.join(group, ni)

  def send() = {
    val buf = ByteBuffer.allocate(80)
    buf.put("Hello".getBytes())
    buf.flip()
    dc.connect(new InetSocketAddress("localhost", 9300))
    val cSent = dc.write(buf)
    dc.receive(buf)
    buf.flip()
    val ba: Array[Byte] = new Array(5)
    buf.get(ba)
    val s = new String(ba)
    s
  }
}

