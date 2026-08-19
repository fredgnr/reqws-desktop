package com.reqws.goland.project

import com.reqws.goland.sync.SyncTrigger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncReadRequestTrackerTest {
  @Test
  fun `a newer automatic read inherits trust-transition intent from a stale generation`() {
    val tracker = SyncReadRequestTracker()
    val transition = tracker.begin(SyncTrigger.TRUST_TRANSITION)
    val automatic = tracker.begin(SyncTrigger.AUTOMATIC)
    var offeredTrigger: SyncTrigger? = null

    assertFalse(tracker.offerCandidateIfLatest(transition) { true })
    assertEquals(SyncTrigger.TRUST_TRANSITION, tracker.pendingReconcileIntent())
    assertTrue(
      tracker.offerCandidateIfLatest(automatic) { trigger ->
        offeredTrigger = trigger
        true
      },
    )

    assertEquals(SyncTrigger.TRUST_TRANSITION, offeredTrigger)
    assertEquals(null, tracker.pendingReconcileIntent())
  }

  @Test
  fun `a read failure preserves trust-transition intent for the next valid candidate`() {
    val tracker = SyncReadRequestTracker()
    tracker.begin(SyncTrigger.TRUST_TRANSITION)
    val failedAutomatic = tracker.begin(SyncTrigger.AUTOMATIC)
    var failureTrigger: SyncTrigger? = null

    assertTrue(
      tracker.runIfLatest(failedAutomatic) { trigger -> failureTrigger = trigger },
    )
    assertEquals(SyncTrigger.TRUST_TRANSITION, failureTrigger)
    assertEquals(SyncTrigger.TRUST_TRANSITION, tracker.pendingReconcileIntent())

    val recoveredAutomatic = tracker.begin(SyncTrigger.AUTOMATIC)
    var recoveryTrigger: SyncTrigger? = null
    assertTrue(
      tracker.offerCandidateIfLatest(recoveredAutomatic) { trigger ->
        recoveryTrigger = trigger
        true
      },
    )
    assertEquals(SyncTrigger.TRUST_TRANSITION, recoveryTrigger)
    assertEquals(null, tracker.pendingReconcileIntent())
  }

  @Test
  fun `manual intent wins when it overlaps a trust-transition replay`() {
    val tracker = SyncReadRequestTracker()
    tracker.begin(SyncTrigger.TRUST_TRANSITION)
    tracker.begin(SyncTrigger.MANUAL)
    val automatic = tracker.begin(SyncTrigger.AUTOMATIC)
    var offeredTrigger: SyncTrigger? = null

    assertTrue(
      tracker.offerCandidateIfLatest(automatic) { trigger ->
        offeredTrigger = trigger
        true
      },
    )

    assertEquals(SyncTrigger.MANUAL, offeredTrigger)
    assertEquals(null, tracker.pendingReconcileIntent())
  }

  @Test
  fun `a newer automatic read inherits manual intent from a stale generation`() {
    val tracker = SyncReadRequestTracker()
    val manual = tracker.begin(SyncTrigger.MANUAL)
    val automatic = tracker.begin(SyncTrigger.AUTOMATIC)
    val offeredTriggers = mutableListOf<SyncTrigger>()

    assertFalse(tracker.offerCandidateIfLatest(manual) { true })
    assertTrue(tracker.hasPendingManualIntent())
    assertTrue(
      tracker.offerCandidateIfLatest(automatic) { trigger ->
        offeredTriggers += trigger
        true
      },
    )

    assertEquals(listOf(SyncTrigger.MANUAL), offeredTriggers)
    assertFalse(tracker.hasPendingManualIntent())
  }

  @Test
  fun `a read failure reports manual intent without consuming it`() {
    val tracker = SyncReadRequestTracker()
    tracker.begin(SyncTrigger.MANUAL)
    val failedAutomatic = tracker.begin(SyncTrigger.AUTOMATIC)
    val failureTriggers = mutableListOf<SyncTrigger>()

    assertTrue(
      tracker.runIfLatest(failedAutomatic) { trigger -> failureTriggers += trigger },
    )
    assertEquals(listOf(SyncTrigger.MANUAL), failureTriggers)
    assertTrue(tracker.hasPendingManualIntent())

    val recoveredAutomatic = tracker.begin(SyncTrigger.AUTOMATIC)
    var recoveryTrigger: SyncTrigger? = null
    assertTrue(
      tracker.offerCandidateIfLatest(recoveredAutomatic) { trigger ->
        recoveryTrigger = trigger
        true
      },
    )
    assertEquals(SyncTrigger.MANUAL, recoveryTrigger)
    assertFalse(tracker.hasPendingManualIntent())
  }

  @Test
  fun `a rejected candidate offer leaves manual intent pending`() {
    val tracker = SyncReadRequestTracker()
    val manual = tracker.begin(SyncTrigger.MANUAL)

    assertFalse(tracker.offerCandidateIfLatest(manual) { false })
    assertTrue(tracker.hasPendingManualIntent())

    val automatic = tracker.begin(SyncTrigger.AUTOMATIC)
    var retryTrigger: SyncTrigger? = null
    assertTrue(
      tracker.offerCandidateIfLatest(automatic) { trigger ->
        retryTrigger = trigger
        true
      },
    )
    assertEquals(SyncTrigger.MANUAL, retryTrigger)
    assertFalse(tracker.hasPendingManualIntent())
  }

  @Test
  fun `a successful automatic candidate stays automatic without pending manual intent`() {
    val tracker = SyncReadRequestTracker()
    val automatic = tracker.begin(SyncTrigger.AUTOMATIC)
    var offeredTrigger: SyncTrigger? = null

    assertTrue(
      tracker.offerCandidateIfLatest(automatic) { trigger ->
        offeredTrigger = trigger
        true
      },
    )

    assertEquals(SyncTrigger.AUTOMATIC, offeredTrigger)
    assertFalse(tracker.hasPendingManualIntent())
  }

  @Test
  fun `service invalidation rejects the active generation and clears manual intent`() {
    val tracker = SyncReadRequestTracker()
    val manual = tracker.begin(SyncTrigger.MANUAL)

    tracker.invalidate()

    assertFalse(tracker.offerCandidateIfLatest(manual) { true })
    assertFalse(tracker.hasPendingManualIntent())
  }
}
