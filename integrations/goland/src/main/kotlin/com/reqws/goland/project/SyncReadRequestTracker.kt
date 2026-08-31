package com.reqws.goland.project

import com.reqws.goland.sync.SyncTrigger
import com.reqws.goland.sync.mergeReconcileTrigger
import com.reqws.goland.sync.requiresReconciliation

/**
 * Linearizes latest-read selection and carries an unconsumed forced reconciliation request.
 *
 * A newer automatic read may supersede a manual, trust-transition, or project-model-change read's
 * bytes, but it must inherit the forced trigger. A service may also arm an intent when it accepts
 * a blocked state, before the wake-up read exists. The intent is consumed only after the latest
 * valid candidate is accepted by the sync coordinator; stale, failed, individually-cancelled, or
 * rejected reads leave it pending. MANUAL wins over TRUST_TRANSITION, which wins over
 * PROJECT_MODEL_CHANGE; service disposal invalidates all generations and clears the intent.
 */
internal class SyncReadRequestTracker {
  private val lock = Any()
  private var latestGeneration = 0L
  private var nextReconcileEpoch = 0L
  private var pendingReconcileEpoch: Long? = null
  private var pendingReconcileTrigger: SyncTrigger? = null
  private var pendingProjectModelOriginDigest: String? = null
  private var pendingProjectModelEventEpoch: Long? = null

  fun begin(
    trigger: SyncTrigger,
    projectModelOriginDigest: String? = null,
    projectModelEventEpoch: Long? = null,
  ): SyncReadRequest = synchronized(lock) {
    beginLocked(
      trigger = trigger,
      cancellationRecoveryAttempt = 0,
      projectModelOriginDigest = projectModelOriginDigest,
      projectModelEventEpoch = projectModelEventEpoch,
    )
  }

  /** Begins a generation only when [condition] is true at the same selection boundary. */
  fun beginIf(
    trigger: SyncTrigger,
    projectModelOriginDigest: String? = null,
    projectModelEventEpoch: Long? = null,
    condition: () -> Boolean,
  ): SyncReadRequest? = synchronized(lock) {
    if (!condition()) return@synchronized null
    beginLocked(
      trigger = trigger,
      cancellationRecoveryAttempt = 0,
      projectModelOriginDigest = projectModelOriginDigest,
      projectModelEventEpoch = projectModelEventEpoch,
    )
  }

  /**
   * Starts a bounded cancellation-recovery generation only while [predecessor] is still latest.
   * A user/VFS/VCS read that arrived during the retry delay therefore wins without being
   * superseded by the stale timer.
   */
  fun beginCancellationRecoveryIf(
    predecessor: SyncReadRequest,
    condition: () -> Boolean,
  ): SyncReadRequest? = synchronized(lock) {
    if (predecessor.generation != latestGeneration || !condition()) {
      return@synchronized null
    }
    beginLocked(
      trigger = SyncTrigger.AUTOMATIC,
      cancellationRecoveryAttempt = predecessor.cancellationRecoveryAttempt + 1,
      projectModelOriginDigest = null,
      projectModelEventEpoch = null,
    )
  }

  private fun beginLocked(
    trigger: SyncTrigger,
    cancellationRecoveryAttempt: Int,
    projectModelOriginDigest: String?,
    projectModelEventEpoch: Long?,
  ): SyncReadRequest {
    val carriesProjectModelLineage =
      projectModelOriginDigest != null && projectModelEventEpoch != null
    require(
      (trigger == SyncTrigger.PROJECT_MODEL_FOLLOW_UP) == carriesProjectModelLineage &&
        (projectModelOriginDigest == null) == (projectModelEventEpoch == null),
    ) {
      "A project-model follow-up requires an origin digest and event epoch"
    }
    latestGeneration += 1
    if (trigger.requiresReconciliation) {
      armReconcileIntentLocked(
        trigger = trigger,
        projectModelOriginDigest = projectModelOriginDigest,
        projectModelEventEpoch = projectModelEventEpoch,
      )
    }
    val effectiveTrigger = pendingReconcileTrigger ?: trigger
    return SyncReadRequest(
      generation = latestGeneration,
      requestedTrigger = trigger,
      cancellationRecoveryAttempt = cancellationRecoveryAttempt,
      projectModelOriginDigest = pendingProjectModelOriginDigest.takeIf {
        effectiveTrigger == SyncTrigger.PROJECT_MODEL_FOLLOW_UP
      },
      projectModelEventEpoch = pendingProjectModelEventEpoch.takeIf {
        effectiveTrigger == SyncTrigger.PROJECT_MODEL_FOLLOW_UP
      },
    )
  }

  fun runIfLatest(
    request: SyncReadRequest,
    action: (SyncTrigger) -> Unit,
  ): Boolean = synchronized(lock) {
    if (request.generation != latestGeneration) return@synchronized false
    action(effectiveTrigger(request))
    true
  }

  /**
   * Conditionally accepts a blocked read's force intent before slower post-read work. A later
   * automatic read may supersede [request], but it inherits the already-armed intent.
   */
  fun runIfLatestAndArmReconcile(
    request: SyncReadRequest,
    trigger: SyncTrigger,
    action: () -> Unit,
  ): Boolean = synchronized(lock) {
    require(trigger.requiresReconciliation) {
      "Only a forced reconciliation trigger can be armed"
    }
    if (request.generation != latestGeneration) return@synchronized false
    armReconcileIntentLocked(
      trigger = trigger,
      projectModelOriginDigest = null,
      projectModelEventEpoch = null,
    )
    action()
    true
  }

  fun offerCandidateIfLatest(
    request: SyncReadRequest,
    offer: (SyncTrigger) -> Boolean,
  ): Boolean = synchronized(lock) {
    if (request.generation != latestGeneration) return@synchronized false
    val reconcileEpoch = pendingReconcileEpoch
    val trigger = effectiveTrigger(request)
    val offered = offer(trigger)
    if (
      offered &&
      reconcileEpoch != null &&
      pendingReconcileEpoch == reconcileEpoch
    ) {
      pendingReconcileEpoch = null
      pendingReconcileTrigger = null
      pendingProjectModelOriginDigest = null
      pendingProjectModelEventEpoch = null
    }
    offered
  }

  fun invalidate() {
    synchronized(lock) {
      latestGeneration += 1
      pendingReconcileEpoch = null
      pendingReconcileTrigger = null
      pendingProjectModelOriginDigest = null
      pendingProjectModelEventEpoch = null
    }
  }

  internal fun hasPendingManualIntent(): Boolean = synchronized(lock) {
    pendingReconcileTrigger == SyncTrigger.MANUAL
  }

  internal fun pendingReconcileIntent(): SyncTrigger? = synchronized(lock) {
    pendingReconcileTrigger
  }

  private fun effectiveTrigger(request: SyncReadRequest): SyncTrigger =
    pendingReconcileTrigger ?: request.requestedTrigger

  private fun armReconcileIntentLocked(
    trigger: SyncTrigger,
    projectModelOriginDigest: String?,
    projectModelEventEpoch: Long?,
  ) {
    nextReconcileEpoch += 1
    pendingReconcileEpoch = nextReconcileEpoch
    val mergedTrigger = mergeReconcileTrigger(pendingReconcileTrigger, trigger)
    pendingReconcileTrigger = mergedTrigger
    if (mergedTrigger == SyncTrigger.PROJECT_MODEL_FOLLOW_UP) {
      if (trigger == SyncTrigger.PROJECT_MODEL_FOLLOW_UP) {
        pendingProjectModelOriginDigest = projectModelOriginDigest
        pendingProjectModelEventEpoch = projectModelEventEpoch
      }
    } else {
      pendingProjectModelOriginDigest = null
      pendingProjectModelEventEpoch = null
    }
  }
}

internal data class SyncReadRequest(
  val generation: Long,
  val requestedTrigger: SyncTrigger,
  val cancellationRecoveryAttempt: Int,
  val projectModelOriginDigest: String?,
  val projectModelEventEpoch: Long?,
)
