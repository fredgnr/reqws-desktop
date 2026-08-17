package com.reqws.goland.sync

import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LatestWinsSyncCoordinatorTest {
  @Test
  fun `same digest is a no-op after a successful apply`() = runBlocking {
    val applied = mutableListOf<String>()
    val events = eventChannel()
    val coordinator = coordinator(
      scope = this,
      events = events,
      apply = { applied += it.value },
    )

    assertTrue(coordinator.offer(candidate("a")))
    assertEvent<SyncCoordinatorEvent.Applied>(events, "a")
    assertTrue(coordinator.offer(candidate("a"), SyncTrigger.MANUAL))
    val noOp = assertEvent<SyncCoordinatorEvent.NoOp>(events, "a")

    assertEquals(SyncTrigger.MANUAL, noOp.trigger)
    assertEquals(listOf("a"), applied)
    assertEquals("a", coordinator.lastAppliedDigest)
    coordinator.close()
  }

  @Test
  fun `an apply in progress is followed only by the newest pending candidate`() = runBlocking {
    val firstApplyStarted = CompletableDeferred<Unit>()
    val releaseFirstApply = CompletableDeferred<Unit>()
    val applied = mutableListOf<String>()
    val events = eventChannel()
    val coordinator = coordinator(
      scope = this,
      events = events,
      apply = { candidate ->
        if (candidate.value == "a") {
          firstApplyStarted.complete(Unit)
          releaseFirstApply.await()
        }
        applied += candidate.value
      },
    )

    coordinator.offer(candidate("a"))
    firstApplyStarted.await()
    coordinator.offer(candidate("b"))
    coordinator.offer(candidate("c"))
    releaseFirstApply.complete(Unit)
    assertEvent<SyncCoordinatorEvent.Applied>(events, "a")
    assertEvent<SyncCoordinatorEvent.Applied>(events, "c")

    assertEquals(listOf("a", "c"), applied)
    assertEquals("c", coordinator.lastAppliedDigest)
    coordinator.close()
  }

  @Test
  fun `a read failure preserves the last digest and a later candidate recovers`() = runBlocking {
    val events = eventChannel()
    val applied = mutableListOf<String>()
    val coordinator = coordinator(
      scope = this,
      events = events,
      initialAppliedDigest = "stable",
      apply = { applied += it.value },
    )

    coordinator.offerReadFailure(
      cause = IllegalArgumentException("invalid manifest"),
      digestSha256 = "invalid",
    )
    val failure = assertEvent<SyncCoordinatorEvent.Failed>(events, "invalid")
    assertEquals(SyncFailureStage.READ, failure.stage)
    assertEquals("stable", coordinator.lastAppliedDigest)

    coordinator.offer(candidate("recovered"))
    assertEvent<SyncCoordinatorEvent.Applied>(events, "recovered")
    assertEquals(listOf("recovered"), applied)
    assertEquals("recovered", coordinator.lastAppliedDigest)
    coordinator.close()
  }

  @Test
  fun `manual and automatic submissions share one latest-wins queue`() = runBlocking {
    val firstApplyStarted = CompletableDeferred<Unit>()
    val releaseFirstApply = CompletableDeferred<Unit>()
    val applied = mutableListOf<String>()
    val events = eventChannel()
    val coordinator = coordinator(
      scope = this,
      events = events,
      apply = { candidate ->
        if (candidate.value == "base") {
          firstApplyStarted.complete(Unit)
          releaseFirstApply.await()
        }
        applied += candidate.value
      },
    )

    coordinator.offer(candidate("base"), SyncTrigger.AUTOMATIC)
    firstApplyStarted.await()
    coordinator.offer(candidate("automatic"), SyncTrigger.AUTOMATIC)
    coordinator.offer(candidate("manual"), SyncTrigger.MANUAL)
    releaseFirstApply.complete(Unit)
    assertEvent<SyncCoordinatorEvent.Applied>(events, "base")
    val manualApplied = assertEvent<SyncCoordinatorEvent.Applied>(events, "manual")

    assertEquals(SyncTrigger.MANUAL, manualApplied.trigger)
    assertEquals(listOf("base", "manual"), applied)
    coordinator.close()
  }

  @Test
  fun `an apply exception does not advance the digest or deadlock the worker`() = runBlocking {
    val events = eventChannel()
    val activeApplies = AtomicInteger(0)
    val maximumActiveApplies = AtomicInteger(0)
    val applied = mutableListOf<String>()
    val coordinator = coordinator(
      scope = this,
      events = events,
      apply = { candidate ->
        val active = activeApplies.incrementAndGet()
        maximumActiveApplies.updateAndGet { previous -> maxOf(previous, active) }
        try {
          if (candidate.value == "broken") error("apply failed")
          applied += candidate.value
        } finally {
          activeApplies.decrementAndGet()
        }
      },
    )

    coordinator.offer(candidate("broken"))
    val failure = assertEvent<SyncCoordinatorEvent.Failed>(events, "broken")
    assertEquals(SyncFailureStage.APPLY, failure.stage)
    assertNull(coordinator.lastAppliedDigest)

    coordinator.offer(candidate("healthy"))
    assertEvent<SyncCoordinatorEvent.Applied>(events, "healthy")
    assertEquals(listOf("healthy"), applied)
    assertEquals(1, maximumActiveApplies.get())
    coordinator.close()
  }

  @Test
  fun `replays the previous digest after a different candidate partially applies and fails`() = runBlocking {
    val events = eventChannel()
    val attempts = mutableListOf<String>()
    val coordinator = coordinator(
      scope = this,
      events = events,
      apply = { candidate ->
        attempts += candidate.value
        if (candidate.value == "b") error("later projection failed")
      },
    )

    coordinator.offer(candidate("a"))
    assertEvent<SyncCoordinatorEvent.Applied>(events, "a")
    coordinator.offer(candidate("b"))
    assertEvent<SyncCoordinatorEvent.Failed>(events, "b")
    assertNull(coordinator.lastAppliedDigest)

    coordinator.offer(candidate("a"))
    assertEvent<SyncCoordinatorEvent.Applied>(events, "a")

    assertEquals(listOf("a", "b", "a"), attempts)
    assertEquals("a", coordinator.lastAppliedDigest)
    coordinator.close()
  }

  @Test
  fun `dispose cancels an in-flight candidate and rejects future work`() = runBlocking {
    val applyStarted = CompletableDeferred<Unit>()
    val neverRelease = CompletableDeferred<Unit>()
    val applied = mutableListOf<String>()
    val events = eventChannel()
    val coordinator = coordinator(
      scope = this,
      events = events,
      apply = { candidate ->
        applyStarted.complete(Unit)
        neverRelease.await()
        applied += candidate.value
      },
    )

    coordinator.offer(candidate("pending"))
    applyStarted.await()
    coordinator.close()
    coordinator.awaitClosed()

    assertFalse(coordinator.offer(candidate("after-dispose")))
    assertTrue(coordinator.isClosed)
    assertNull(coordinator.lastAppliedDigest)
    assertTrue(applied.isEmpty())
  }

  @Test
  fun `cancelling the owner scope stops the worker and rejects future work`() = runBlocking {
    val owner = CoroutineScope(coroutineContext + Job(coroutineContext[Job]))
    val coordinator = LatestWinsSyncCoordinator(
      scope = owner,
      applier = SyncCandidateApplier<String> { error("must not run") },
    )

    owner.cancel()
    coordinator.awaitClosed()

    assertFalse(coordinator.offer(candidate("after-cancel")))
    assertTrue(coordinator.isClosed)
  }

  @Test
  fun `an observer exception cannot terminate synchronization`() = runBlocking {
    val applied = Channel<String>(Channel.UNLIMITED)
    val coordinator = LatestWinsSyncCoordinator(
      scope = this,
      applier = SyncCandidateApplier { candidate -> applied.send(candidate.value) },
      observer = SyncCoordinatorObserver { error("observer failed") },
    )

    coordinator.offer(candidate("first"))
    assertEquals("first", applied.receive())
    coordinator.offer(candidate("second"))
    assertEquals("second", applied.receive())
    coordinator.close()
  }

  private fun candidate(value: String) = SyncCandidate(digestSha256 = value, value = value)

  private fun eventChannel() = Channel<SyncCoordinatorEvent>(Channel.UNLIMITED)

  private fun coordinator(
    scope: CoroutineScope,
    events: Channel<SyncCoordinatorEvent>,
    initialAppliedDigest: String? = null,
    apply: suspend (SyncCandidate<String>) -> Unit,
  ) = LatestWinsSyncCoordinator(
    scope = scope,
    initialAppliedDigest = initialAppliedDigest,
    applier = SyncCandidateApplier(apply),
    observer = SyncCoordinatorObserver { events.trySend(it) },
  )

  private suspend inline fun <reified T : SyncCoordinatorEvent> assertEvent(
    events: Channel<SyncCoordinatorEvent>,
    digest: String,
  ): T {
    while (true) {
      val event = events.receive()
      if (event is T && event.digestSha256 == digest) return event
    }
  }
}
