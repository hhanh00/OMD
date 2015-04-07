package com.hanh

import org.scalatest.FunSuite

class RingBufferSuite extends FunSuite {
  test("A RingBuffer should accept a message") {
    val rb = new RingBuffer
    val m = Message(10, 0, Array.empty)
    rb.add(m)
    val m2 = rb.takeAvailable()
    assert(m === m2.head)
  }

  test("A RingBuffer should accept two messages in a row") {
    val rb = new RingBuffer
    val m = Message(10, 0, Array.empty)
    rb.add(m)
    val m2 = Message(11, 0, Array.empty)
    rb.add(m2)
    val av = rb.takeAvailable()
    assert(m === av(0))
    assert(m2 === av(1))
  }

  test("A RingBuffer should only return the first message if there is a gap") {
    val rb = new RingBuffer
    val m = Message(10, 0, Array.empty)
    rb.add(m)
    val m2 = Message(12, 0, Array.empty)
    rb.add(m2)
    val av = rb.takeAvailable()
    assert(m === av(0))
    assert(av.length == 1)
  }

  test("A RingBuffer should accept messages out of sequence") {
    val rb = new RingBuffer
    val m = Message(10, 0, Array.empty)
    rb.add(m)
    val m2 = Message(12, 0, Array.empty)
    rb.add(m2)
    val av = rb.takeAvailable()
    assert(m === av(0))
    assert(av.length == 1)

    val m3 = Message(11, 0, Array.empty)
    rb.add(m3)
    val av2 = rb.takeAvailable()
    assert(m3 === av2(0))
    assert(m2 === av2(1))
    assert(av2.length == 2)
  }

  test("A RingBuffer should accept up to maxSize messages") {
    val rb = new RingBuffer
    val m = for (i <- 0 until RingBuffer.maxSize) yield {
      Message(10 + i, 0, Array.empty)
    }
    m foreach (rb.add(_))
    val av = rb.takeAvailable()
    assert(av === m)
  }

  test("A RingBuffer should not accept more than maxSize messages") {
    val rb = new RingBuffer
    val m = for (i <- 0 until RingBuffer.maxSize+1) yield {
      Message(10 + i, 0, Array.empty)
    }
    intercept[RuntimeException] {
      m foreach (rb.add(_))
    }
  }

  test("A RingBuffer can continuously accept messages if they are consumed") {
    val rb = new RingBuffer
    for (i <- 0 until 2*RingBuffer.maxSize) {
      val m = Message(10 + i, 0, Array.empty)
      rb.add(m)
      val av = rb.takeAvailable()
      assert(av(0) === m)
    }
    assert(rb.isEmpty)
  }

  test("A RingBuffer can continuously accept messages if they are consumed even out of order") {
    val rb = new RingBuffer
    for (i <- 0 until 2*RingBuffer.maxSize) {
      val m = Message(10 + i, 0, Array.empty)
      val m2 = Message(12 + i, 0, Array.empty)
      rb.add(m)
      rb.add(m2)
      val av = rb.takeAvailable()
    }
    assert(rb.isEmpty)
  }
}

class NetworkSuite extends FunSuite {
  test("send/receive") {
    val n = new MulticastActor
    val s = n.send()
    println(s)
  }
}