package com.reqws.goland.project

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

class TerminalStatePublisherTest {
  @Test
  fun `serializes reentrant publications and rejects values after terminal state`() {
    val publisher = TerminalStatePublisher("initial") { it == "disposed" }
    val observed = mutableListOf<String>()

    publisher.addListener { state ->
      observed.add(state)
      if (state == "initial") {
        assertTrue(publisher.publish("next"))
        assertTrue(publisher.publish("disposed"))
        assertFalse(publisher.publish("late"))
      }
    }

    assertEquals(listOf("initial", "next", "disposed"), observed)
    assertEquals("disposed", publisher.state)
  }

  @Test
  fun `orders a concurrent terminal notification after an in-flight publication`() {
    val publisher = TerminalStatePublisher("initial") { it == "disposed" }
    val observed = CopyOnWriteArrayList<String>()
    val nextEntered = CountDownLatch(1)
    val releaseNext = CountDownLatch(1)
    publisher.addListener { state ->
      if (state == "next") {
        nextEntered.countDown()
        assertTrue(releaseNext.await(5, TimeUnit.SECONDS))
      }
      observed.add(state)
    }

    val publishing = thread { assertTrue(publisher.publish("next")) }
    assertTrue(nextEntered.await(5, TimeUnit.SECONDS))
    val disposing = thread { assertTrue(publisher.publish("disposed")) }
    disposing.join(5_000)
    assertFalse(disposing.isAlive)
    releaseNext.countDown()
    publishing.join(5_000)
    assertFalse(publishing.isAlive)

    assertEquals(listOf("initial", "next", "disposed"), observed)
    assertEquals("disposed", publisher.state)
    assertFalse(publisher.publish("late"))
  }

  @Test
  fun `a listener added after termination observes only the terminal state`() {
    val publisher = TerminalStatePublisher("initial") { it == "disposed" }
    assertTrue(publisher.publish("disposed"))
    val observed = mutableListOf<String>()

    publisher.addListener(observed::add).close()

    assertEquals(listOf("disposed"), observed)
  }

  @Test
  fun `versioned transient publication carries its stable baseline and defers listeners`() {
    val publisher = TerminalStatePublisher(
      initialState = "stable-0",
      isTerminal = { it == "disposed" },
      isStable = { !it.startsWith("transient-") },
    )
    val observed = mutableListOf<String>()
    publisher.addListener(observed::add)

    val reading = requireNotNull(
      publisher.prepareUpdate { current ->
        assertEquals("stable-0", current.state)
        assertEquals("stable-0", current.stableState)
        "transient-reading"
      },
    )

    assertEquals("transient-reading", publisher.state)
    assertEquals(listOf("stable-0"), observed)
    assertEquals("stable-0", reading.after.stableState)
    reading.deliver()
    assertEquals(listOf("stable-0", "transient-reading"), observed)

    val rollback = requireNotNull(
      publisher.prepareCompareAndPublish(
        expectedVersion = reading.after.version,
        next = reading.after.stableState,
      ),
    )
    rollback.deliver()

    assertEquals("stable-0", publisher.state)
    assertEquals(listOf("stable-0", "transient-reading", "stable-0"), observed)
  }

  @Test
  fun `stale publication token cannot overwrite a newer stable state`() {
    val publisher = TerminalStatePublisher(
      initialState = "stable-0",
      isTerminal = { it == "disposed" },
      isStable = { !it.startsWith("transient-") },
    )
    val reading = requireNotNull(
      publisher.prepareUpdate { "transient-reading" },
    )
    reading.deliver()
    assertTrue(publisher.publish("stable-applied"))

    assertNull(
      publisher.prepareCompareAndPublish(
        expectedVersion = reading.after.version,
        next = reading.after.stableState,
      ),
    )
    assertEquals("stable-applied", publisher.state)
    assertEquals("stable-applied", publisher.snapshot().stableState)
  }
}
