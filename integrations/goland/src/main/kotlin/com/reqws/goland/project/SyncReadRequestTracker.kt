package com.reqws.goland.project

import com.reqws.goland.sync.SyncTrigger
import com.reqws.goland.sync.mergeReconcileTrigger
import com.reqws.goland.sync.requiresReconciliation

/**
 * Linearizes latest-read selection and carries an unconsumed forced reconciliation request.
 *
 * A newer automatic read may supersede a manual or trust-transition read's bytes, but it must
 * inherit the forced trigger. A service may also arm an intent when it accepts a blocked state,
 * before the wake-up read exists. The intent is consumed only after the latest valid candidate is
 * accepted by the sync coordinator; stale, failed, individually-cancelled, or rejected reads
 * leave it pending. MANUAL wins if both kinds overlap. Service disposal invalidates all
 * generations and clears the intent.
 */
internal class SyncReadRequestTracker {
  private val lock = Any()
  private var latestGeneration = 0L
  private var nextReconcileEpoch = 0L
  private var pendingReconcileEpoch: Long? = null
  private var pendingReconcileTrigger: SyncTrigger? = null

  fun begin(trigger: SyncTrigger): SyncReadRequest = synchronized(lock) {
    beginLocked(trigger)
  }

  /** Begins a generation only when [condition] is true at the same selection boundary. */
  fun beginIf(
    trigger: SyncTrigger,
    condition: () -> Boolean,
  ): SyncReadRequest? = synchronized(lock) {
    if (!condition()) return@synchronized null
    beginLocked(trigger)
  }

  private fun beginLocked(trigger: SyncTrigger): SyncReadRequest {
    latestGeneration += 1
    if (trigger.requiresReconciliation) {
      armReconcileIntentLocked(trigger)
    }
    return SyncReadRequest(
      generation = latestGeneration,
      requestedTrigger = trigger,
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
    armReconcileIntentLocked(trigger)
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
    }
    offered
  }

  fun invalidate() {
    synchronized(lock) {
      latestGeneration += 1
      pendingReconcileEpoch = null
      pendingReconcileTrigger = null
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

  private fun armReconcileIntentLocked(trigger: SyncTrigger) {
    nextReconcileEpoch += 1
    pendingReconcileEpoch = nextReconcileEpoch
    pendingReconcileTrigger = mergeReconcileTrigger(pendingReconcileTrigger, trigger)
  }
}

internal data class SyncReadRequest(
  val generation: Long,
  val requestedTrigger: SyncTrigger,
)
