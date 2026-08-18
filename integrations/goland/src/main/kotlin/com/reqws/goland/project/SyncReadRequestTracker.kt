package com.reqws.goland.project

import com.reqws.goland.sync.SyncTrigger

/**
 * Linearizes latest-read selection and carries an unconsumed manual reconciliation request.
 *
 * A newer automatic read may supersede the manual read's bytes, but it must inherit the manual
 * trigger. The intent is consumed only after the latest valid candidate is accepted by the sync
 * coordinator; stale, failed, individually-cancelled, or rejected reads leave it pending. Service
 * disposal invalidates all generations and clears the intent.
 */
internal class SyncReadRequestTracker {
  private val lock = Any()
  private var latestGeneration = 0L
  private var nextManualEpoch = 0L
  private var pendingManualEpoch: Long? = null

  fun begin(trigger: SyncTrigger): SyncReadRequest = synchronized(lock) {
    latestGeneration += 1
    if (trigger == SyncTrigger.MANUAL) {
      nextManualEpoch += 1
      pendingManualEpoch = nextManualEpoch
    }
    SyncReadRequest(
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

  fun offerCandidateIfLatest(
    request: SyncReadRequest,
    offer: (SyncTrigger) -> Boolean,
  ): Boolean = synchronized(lock) {
    if (request.generation != latestGeneration) return@synchronized false
    val manualEpoch = pendingManualEpoch
    val trigger = effectiveTrigger(request)
    val offered = offer(trigger)
    if (offered && manualEpoch != null && pendingManualEpoch == manualEpoch) {
      pendingManualEpoch = null
    }
    offered
  }

  fun invalidate() {
    synchronized(lock) {
      latestGeneration += 1
      pendingManualEpoch = null
    }
  }

  internal fun hasPendingManualIntent(): Boolean = synchronized(lock) {
    pendingManualEpoch != null
  }

  private fun effectiveTrigger(request: SyncReadRequest): SyncTrigger =
    if (pendingManualEpoch != null) SyncTrigger.MANUAL else request.requestedTrigger
}

internal data class SyncReadRequest(
  val generation: Long,
  val requestedTrigger: SyncTrigger,
)
