package com.reqws.goland.project

import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TrustTransitionMonitorTest {
  @Test
  fun `fires once when a blocked project becomes trusted`() = runBlocking {
    val trusted = AtomicBoolean(false)
    val polls = CompletableDeferred<Unit>()
    val actions = AtomicInteger(0)
    val scope = CoroutineScope(coroutineContext + SupervisorJob())
    val monitor = TrustTransitionMonitor(
      scope = scope,
      probe = TrustStateProbe { trusted.get() },
      action = TrustedTransitionAction { actions.incrementAndGet() },
      waiter = TrustPollWaiter {
        polls.complete(Unit)
        while (!trusted.get()) yield()
      },
    )

    assertTrue(monitor.awaitTrusted())
    assertTrue(monitor.awaitTrusted())
    polls.await()
    trusted.set(true)
    awaitCondition { actions.get() == 1 }

    assertEquals(1, actions.get())
    monitor.close()
    scope.cancel()
  }

  @Test
  fun `cancellation prevents a later transition callback`() = runBlocking {
    val trusted = AtomicBoolean(false)
    val waiting = CompletableDeferred<Unit>()
    val actions = AtomicInteger(0)
    val scope = CoroutineScope(coroutineContext + SupervisorJob())
    val monitor = TrustTransitionMonitor(
      scope = scope,
      probe = TrustStateProbe { trusted.get() },
      action = TrustedTransitionAction { actions.incrementAndGet() },
      waiter = TrustPollWaiter {
        waiting.complete(Unit)
        while (true) yield()
      },
    )

    assertTrue(monitor.awaitTrusted())
    waiting.await()
    monitor.cancelPending()
    trusted.set(true)
    repeat(10) { yield() }

    assertEquals(0, actions.get())
    monitor.close()
    scope.cancel()
  }

  @Test
  fun `rearms when a project becomes blocked again before transition action completes`() =
    runBlocking {
      val trusted = AtomicBoolean(false)
      val firstPollStarted = CompletableDeferred<Unit>()
      val firstActionStarted = CompletableDeferred<Unit>()
      val releaseFirstAction = CompletableDeferred<Unit>()
      val secondPollStarted = CompletableDeferred<Unit>()
      val waits = AtomicInteger(0)
      val actions = AtomicInteger(0)
      val scope = CoroutineScope(coroutineContext + SupervisorJob())
      val monitor = TrustTransitionMonitor(
        scope = scope,
        probe = TrustStateProbe { trusted.get() },
        action = TrustedTransitionAction {
          if (actions.incrementAndGet() == 1) {
            firstActionStarted.complete(Unit)
            releaseFirstAction.await()
          }
        },
        waiter = TrustPollWaiter {
          when (waits.incrementAndGet()) {
            1 -> firstPollStarted.complete(Unit)
            2 -> secondPollStarted.complete(Unit)
          }
          while (!trusted.get()) yield()
        },
      )

      try {
        withTimeout(5_000) {
          assertTrue(monitor.awaitTrusted())
          firstPollStarted.await()
          trusted.set(true)
          firstActionStarted.await()

          // This models the forced refresh loading SAFE_MODE_BLOCKED before the old polling job's
          // completion callback has cleared it.
          trusted.set(false)
          assertTrue(monitor.awaitTrusted())
          releaseFirstAction.complete(Unit)
          secondPollStarted.await()

          trusted.set(true)
          awaitCondition { actions.get() == 2 }
          assertEquals(2, actions.get())
        }
      } finally {
        monitor.close()
        scope.cancel()
      }
    }

  @Test
  fun `rejects scheduling after close`() = runBlocking {
    val scope = CoroutineScope(coroutineContext + SupervisorJob())
    val monitor = TrustTransitionMonitor(
      scope = scope,
      probe = TrustStateProbe { false },
      action = TrustedTransitionAction {},
    )

    monitor.close()

    assertFalse(monitor.awaitTrusted())
    scope.cancel()
  }

  private suspend fun awaitCondition(condition: () -> Boolean) {
    repeat(1_000) {
      if (condition()) return
      yield()
    }
    throw AssertionError("Condition was not satisfied")
  }
}
