package com.reqws.goland.sync

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LatestDebouncerTest {
  @Test
  fun `a burst emits only the latest value after the debounce gate`() = runBlocking {
    val waiter = ControlledWaiter()
    val values = Channel<String>(Channel.UNLIMITED)
    val debouncer = LatestDebouncer<String>(
      scope = this,
      waiter = waiter,
      action = DebouncedAction<String> { values.send(it) },
    )

    debouncer.submit("a")
    val firstGate = waiter.nextGate()
    debouncer.submit("b")
    val secondGate = waiter.nextGate()
    debouncer.submit("c")
    val thirdGate = waiter.nextGate()
    firstGate.complete(Unit)
    secondGate.complete(Unit)
    thirdGate.complete(Unit)

    assertEquals("c", values.receive())
    assertTrue(values.tryReceive().isFailure)
    debouncer.close()
  }

  @Test
  fun `submit now cancels a delayed value and uses the same action`() = runBlocking {
    val waiter = ControlledWaiter()
    val values = Channel<String>(Channel.UNLIMITED)
    val debouncer = LatestDebouncer<String>(
      scope = this,
      waiter = waiter,
      action = DebouncedAction<String> { values.send(it) },
    )

    debouncer.submit("automatic")
    val delayedGate = waiter.nextGate()
    debouncer.submitNow("manual")

    assertEquals("manual", values.receive())
    delayedGate.complete(Unit)
    assertTrue(values.tryReceive().isFailure)
    debouncer.close()
  }

  @Test
  fun `an action exception is reported and a later submission recovers`() = runBlocking {
    val waiter = ControlledWaiter()
    val failures = Channel<Throwable>(Channel.UNLIMITED)
    val values = Channel<String>(Channel.UNLIMITED)
    val debouncer = LatestDebouncer<String>(
      scope = this,
      waiter = waiter,
      action = DebouncedAction<String> { value ->
        if (value == "broken") error("callback failed")
        values.send(value)
      },
      onFailure = { failures.trySend(it) },
    )

    debouncer.submit("broken")
    waiter.nextGate().complete(Unit)
    assertEquals("callback failed", failures.receive().message)

    debouncer.submit("healthy")
    waiter.nextGate().complete(Unit)
    assertEquals("healthy", values.receive())
    debouncer.close()
  }

  @Test
  fun `close cancels the pending value and rejects new submissions`() = runBlocking {
    val waiter = ControlledWaiter()
    val values = Channel<String>(Channel.UNLIMITED)
    val debouncer = LatestDebouncer<String>(
      scope = this,
      waiter = waiter,
      action = DebouncedAction<String> { values.send(it) },
    )

    debouncer.submit("pending")
    val gate = waiter.nextGate()
    debouncer.close()
    gate.complete(Unit)

    assertFalse(debouncer.submit("after-close"))
    assertFalse(debouncer.submitNow("after-close-now"))
    assertTrue(debouncer.isClosed)
    assertTrue(values.tryReceive().isFailure)
  }

  @Test
  fun `the production delay is constrained to the documented range`() {
    assertEquals(350L, LatestDebouncer.DEFAULT_DELAY_MILLIS)
    assertTrue(LatestDebouncer.DEFAULT_DELAY_MILLIS in 250L..500L)
  }

  private class ControlledWaiter : DebounceWaiter {
    private val gates = Channel<CompletableDeferred<Unit>>(Channel.UNLIMITED)

    override suspend fun await(delayMillis: Long) {
      assertTrue(delayMillis in 250L..500L)
      val gate = CompletableDeferred<Unit>()
      gates.send(gate)
      gate.await()
    }

    suspend fun nextGate(): CompletableDeferred<Unit> = gates.receive()
  }
}
