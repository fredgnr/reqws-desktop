package com.reqws.goland.project

import com.reqws.goland.sync.SyncTrigger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncReadRequestTrackerTest {
  @Test
  fun `a cancellation recovery is an automatic successor with an incremented attempt`() {
    val tracker = SyncReadRequestTracker()
    val initial = tracker.begin(SyncTrigger.AUTOMATIC)

    val recovery = tracker.beginCancellationRecoveryIf(initial) { true }

    requireNotNull(recovery)
    assertEquals(SyncTrigger.AUTOMATIC, recovery.requestedTrigger)
    assertEquals(1, recovery.cancellationRecoveryAttempt)
    assertTrue(tracker.runIfLatest(recovery) {})
  }

  @Test
  fun `a stale cancellation timer cannot supersede a newer read`() {
    val tracker = SyncReadRequestTracker()
    val cancelled = tracker.begin(SyncTrigger.AUTOMATIC)
    val newer = tracker.begin(SyncTrigger.MANUAL)

    assertEquals(
      null,
      tracker.beginCancellationRecoveryIf(cancelled) { true },
    )
    assertTrue(tracker.runIfLatest(newer) {})
  }

  @Test
  fun `startup metadata recovery follows the latest read and increments only its own attempt`() {
    val tracker = SyncReadRequestTracker()
    tracker.begin(
      trigger = SyncTrigger.AUTOMATIC,
      armInitialProjectMetadataRecovery = true,
    )
    val latest = tracker.begin(SyncTrigger.AUTOMATIC)

    assertTrue(latest.initialProjectMetadataRecoveryEligible)
    val recovery = tracker.beginProjectMetadataRecoveryIf(latest) { true }

    requireNotNull(recovery)
    assertEquals(0, recovery.cancellationRecoveryAttempt)
    assertEquals(1, recovery.projectMetadataRecoveryAttempt)
    assertTrue(recovery.initialProjectMetadataRecoveryEligible)
  }

  @Test
  fun `a stale metadata readiness monitor cannot supersede a newer read`() {
    val tracker = SyncReadRequestTracker()
    val waiting = tracker.begin(
      trigger = SyncTrigger.AUTOMATIC,
      armInitialProjectMetadataRecovery = true,
    )
    val newer = tracker.begin(SyncTrigger.MANUAL)

    assertEquals(null, tracker.beginProjectMetadataRecoveryIf(waiting) { true })
    assertTrue(newer.initialProjectMetadataRecoveryEligible)
    assertTrue(tracker.completeInitialProjectMetadataRecoveryIfLatest(newer))
    assertFalse(tracker.begin(SyncTrigger.AUTOMATIC).initialProjectMetadataRecoveryEligible)
  }

  @Test
  fun `a newer normal read cannot reset an active project metadata recovery attempt`() {
    val tracker = SyncReadRequestTracker()
    val startup = tracker.begin(
      trigger = SyncTrigger.AUTOMATIC,
      armInitialProjectMetadataRecovery = true,
    )
    val recovery = requireNotNull(tracker.beginProjectMetadataRecoveryIf(startup) { true })

    val newer = tracker.begin(SyncTrigger.AUTOMATIC)

    assertEquals(1, recovery.projectMetadataRecoveryAttempt)
    assertEquals(1, newer.projectMetadataRecoveryAttempt)
    assertTrue(newer.initialProjectMetadataRecoveryEligible)
  }

  @Test
  fun `metadata recovery cannot reset a consumed startup cancellation attempt`() {
    val tracker = SyncReadRequestTracker()
    val startup = tracker.begin(
      trigger = SyncTrigger.AUTOMATIC,
      armInitialProjectMetadataRecovery = true,
    )
    val cancellationRecovery = requireNotNull(
      tracker.beginCancellationRecoveryIf(startup) { true },
    )

    val metadataRecovery = requireNotNull(
      tracker.beginProjectMetadataRecoveryIf(cancellationRecovery) { true },
    )

    assertEquals(1, metadataRecovery.cancellationRecoveryAttempt)
    assertEquals(1, metadataRecovery.projectMetadataRecoveryAttempt)
  }

  @Test
  fun `a rejected conditional begin does not supersede the current generation`() {
    val tracker = SyncReadRequestTracker()
    val current = tracker.begin(SyncTrigger.AUTOMATIC)

    assertEquals(
      null,
      tracker.beginIf(SyncTrigger.AUTOMATIC) { false },
    )
    assertTrue(tracker.runIfLatest(current) {})
  }

  @Test
  fun `an accepted Safe Mode block arms the next automatic candidate`() {
    val tracker = SyncReadRequestTracker()
    val blocked = tracker.begin(SyncTrigger.AUTOMATIC)

    assertTrue(
      tracker.runIfLatestAndArmReconcile(blocked, SyncTrigger.TRUST_TRANSITION) {},
    )
    val trustedAutomatic = tracker.begin(SyncTrigger.AUTOMATIC)
    var offeredTrigger: SyncTrigger? = null
    assertTrue(
      tracker.offerCandidateIfLatest(trustedAutomatic) { trigger ->
        offeredTrigger = trigger
        true
      },
    )

    assertEquals(SyncTrigger.TRUST_TRANSITION, offeredTrigger)
    assertEquals(null, tracker.pendingReconcileIntent())
  }

  @Test
  fun `a stale Safe Mode block cannot arm a newer automatic candidate`() {
    val tracker = SyncReadRequestTracker()
    val staleBlocked = tracker.begin(SyncTrigger.AUTOMATIC)
    val newerAutomatic = tracker.begin(SyncTrigger.AUTOMATIC)

    assertFalse(
      tracker.runIfLatestAndArmReconcile(staleBlocked, SyncTrigger.TRUST_TRANSITION) {},
    )
    var offeredTrigger: SyncTrigger? = null
    assertTrue(
      tracker.offerCandidateIfLatest(newerAutomatic) { trigger ->
        offeredTrigger = trigger
        true
      },
    )

    assertEquals(SyncTrigger.AUTOMATIC, offeredTrigger)
    assertEquals(null, tracker.pendingReconcileIntent())
  }

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
  fun `a newer automatic read inherits project-model intent from a stale generation`() {
    val tracker = SyncReadRequestTracker()
    val changed = tracker.begin(
      trigger = SyncTrigger.PROJECT_MODEL_CHANGE,
    )
    val automatic = tracker.begin(SyncTrigger.AUTOMATIC)
    var offeredTrigger: SyncTrigger? = null

    assertFalse(tracker.offerCandidateIfLatest(changed) { true })
    assertTrue(
      tracker.offerCandidateIfLatest(automatic) { trigger ->
        offeredTrigger = trigger
        true
      },
    )

    assertEquals(SyncTrigger.PROJECT_MODEL_CHANGE, offeredTrigger)
    assertEquals(null, tracker.pendingReconcileIntent())
  }

  @Test
  fun `an accepted older offer cannot consume a reentrant project-model intent`() {
    val tracker = SyncReadRequestTracker()
    val first = tracker.begin(SyncTrigger.PROJECT_MODEL_CHANGE)
    var newer: SyncReadRequest? = null

    assertTrue(tracker.offerCandidateIfLatest(first) {
      newer = tracker.begin(SyncTrigger.PROJECT_MODEL_CHANGE)
      true
    })
    assertEquals(SyncTrigger.PROJECT_MODEL_CHANGE, tracker.pendingReconcileIntent())
    assertFalse(tracker.offerCandidateIfLatest(first) { true })
    assertTrue(tracker.offerCandidateIfLatest(requireNotNull(newer)) { trigger ->
      assertEquals(SyncTrigger.PROJECT_MODEL_CHANGE, trigger)
      true
    })
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
