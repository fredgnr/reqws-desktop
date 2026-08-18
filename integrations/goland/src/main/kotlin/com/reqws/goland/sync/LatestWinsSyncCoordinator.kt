package com.reqws.goland.sync

import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch

internal enum class SyncTrigger {
  AUTOMATIC,
  MANUAL,
}

internal data class SyncCandidate<T>(
  val digestSha256: String,
  val value: T,
) {
  init {
    require(digestSha256.isNotBlank()) { "A sync candidate digest must not be blank" }
  }
}

internal enum class SyncFailureStage {
  READ,
  APPLY,
}

internal sealed interface SyncCoordinatorEvent {
  val requestId: Long
  val trigger: SyncTrigger
  val digestSha256: String?

  data class Applying(
    override val requestId: Long,
    override val trigger: SyncTrigger,
    override val digestSha256: String,
  ) : SyncCoordinatorEvent

  data class Applied(
    override val requestId: Long,
    override val trigger: SyncTrigger,
    override val digestSha256: String,
  ) : SyncCoordinatorEvent

  data class NoOp(
    override val requestId: Long,
    override val trigger: SyncTrigger,
    override val digestSha256: String,
  ) : SyncCoordinatorEvent

  data class Failed(
    override val requestId: Long,
    override val trigger: SyncTrigger,
    override val digestSha256: String?,
    val stage: SyncFailureStage,
    val cause: Throwable,
  ) : SyncCoordinatorEvent
}

internal fun interface SyncCandidateApplier<T> {
  suspend fun apply(candidate: SyncCandidate<T>)
}

internal fun interface SyncCoordinatorObserver {
  fun onEvent(event: SyncCoordinatorEvent)
}

/**
 * Serializes project-model mutations while retaining only the newest pending submission.
 *
 * Reading and validation deliberately happen before [offer]. A VFS or manual-sync adapter
 * should always reread the final manifest bytes and then submit either a [SyncCandidate] or a
 * read failure. One worker owns all apply calls, so a candidate arriving during an apply replaces
 * any older pending candidate without cancelling the in-flight transaction.
 */
internal class LatestWinsSyncCoordinator<T>(
  scope: CoroutineScope,
  initialAppliedDigest: String? = null,
  private val applier: SyncCandidateApplier<T>,
  private val observer: SyncCoordinatorObserver = SyncCoordinatorObserver {},
) : AutoCloseable {
  private val closed = AtomicBoolean(false)
  private val nextRequestId = AtomicLong(0)
  private val appliedDigest = AtomicReference<String?>(initialAppliedDigest)
  private val submissionLock = Any()
  private var pendingSubmission: Submission<T>? = null
  private var manualReconcilePending = false
  private val submissionSignal = Channel<Unit>(Channel.CONFLATED)
  private val worker: Job

  init {
    requireNotNull(scope.coroutineContext[Job]) {
      "LatestWinsSyncCoordinator requires a lifecycle-owned CoroutineScope"
    }
    require(initialAppliedDigest == null || initialAppliedDigest.isNotBlank()) {
      "The initial applied digest must not be blank"
    }
    worker = scope.launch(start = CoroutineStart.UNDISPATCHED) {
      consumeSubmissions()
    }
    worker.invokeOnCompletion {
      closed.set(true)
      synchronized(submissionLock) {
        pendingSubmission = null
        manualReconcilePending = false
      }
      submissionSignal.close()
    }
  }

  val lastAppliedDigest: String?
    get() = appliedDigest.get()

  val isClosed: Boolean
    get() = closed.get()

  fun offer(
    candidate: SyncCandidate<T>,
    trigger: SyncTrigger = SyncTrigger.AUTOMATIC,
  ): Boolean = submit(
    CandidateSubmission(
      requestId = nextRequestId.incrementAndGet(),
      trigger = trigger,
      candidate = candidate,
    ),
  )

  fun offerReadFailure(
    cause: Throwable,
    trigger: SyncTrigger = SyncTrigger.AUTOMATIC,
    digestSha256: String? = null,
  ): Boolean = submit(
    ReadFailureSubmission(
      requestId = nextRequestId.incrementAndGet(),
      trigger = trigger,
      digestSha256 = digestSha256,
      cause = cause,
    ),
  )

  private fun submit(submission: Submission<T>): Boolean {
    if (closed.get() || !worker.isActive) return false
    synchronized(submissionLock) {
      if (closed.get() || !worker.isActive) return false
      if (submission.trigger == SyncTrigger.MANUAL) manualReconcilePending = true
      pendingSubmission = if (
        manualReconcilePending && submission.trigger == SyncTrigger.AUTOMATIC
      ) {
        submission.withTrigger(SyncTrigger.MANUAL)
      } else {
        submission
      }
    }
    return submissionSignal.trySend(Unit).isSuccess
  }

  private suspend fun consumeSubmissions() {
    for (ignored in submissionSignal) {
      currentCoroutineContext().ensureActive()
      val submission = synchronized(submissionLock) {
        val next = pendingSubmission ?: return@synchronized null
        pendingSubmission = null
        if (next is CandidateSubmission && next.trigger == SyncTrigger.MANUAL) {
          // The explicit intent is consumed only when a valid candidate actually starts apply.
          // A read failure keeps it sticky so the next valid automatic read still reconciles.
          manualReconcilePending = false
        }
        next
      } ?: continue
      when (submission) {
        is CandidateSubmission -> apply(submission)
        is ReadFailureSubmission -> notifyObserver(
          SyncCoordinatorEvent.Failed(
            requestId = submission.requestId,
            trigger = submission.trigger,
            digestSha256 = submission.digestSha256,
            stage = SyncFailureStage.READ,
            cause = submission.cause,
          ),
        )
      }
    }
  }

  private suspend fun apply(submission: CandidateSubmission<T>) {
    val candidate = submission.candidate
    // A manual refresh is an explicit reconciliation request: the live project model, VCS
    // mappings, or filesystem may have drifted even when the manifest bytes are unchanged.
    if (
      submission.trigger != SyncTrigger.MANUAL &&
      candidate.digestSha256 == appliedDigest.get()
    ) {
      notifyObserver(
        SyncCoordinatorEvent.NoOp(
          requestId = submission.requestId,
          trigger = submission.trigger,
          digestSha256 = candidate.digestSha256,
        ),
      )
      return
    }

    // An apply may commit one projection layer before a later layer fails. Clear the in-memory
    // no-op baseline before every replay so a later submission cannot accept a potentially
    // partial intermediate state, including after a manual same-digest reconciliation.
    appliedDigest.set(null)
    notifyObserver(
      SyncCoordinatorEvent.Applying(
        requestId = submission.requestId,
        trigger = submission.trigger,
        digestSha256 = candidate.digestSha256,
      ),
    )
    try {
      currentCoroutineContext().ensureActive()
      applier.apply(candidate)
      currentCoroutineContext().ensureActive()
      appliedDigest.set(candidate.digestSha256)
      notifyObserver(
        SyncCoordinatorEvent.Applied(
          requestId = submission.requestId,
          trigger = submission.trigger,
          digestSha256 = candidate.digestSha256,
        ),
      )
    } catch (exception: CancellationException) {
      throw exception
    } catch (exception: Exception) {
      notifyObserver(
        SyncCoordinatorEvent.Failed(
          requestId = submission.requestId,
          trigger = submission.trigger,
          digestSha256 = candidate.digestSha256,
          stage = SyncFailureStage.APPLY,
          cause = exception,
        ),
      )
    }
  }

  private fun notifyObserver(event: SyncCoordinatorEvent) {
    try {
      observer.onEvent(event)
    } catch (_: Exception) {
      // Observability must never terminate the synchronization worker.
    }
  }

  override fun close() {
    if (!closed.compareAndSet(false, true)) return
    synchronized(submissionLock) {
      pendingSubmission = null
      manualReconcilePending = false
    }
    submissionSignal.close()
    worker.cancel(CancellationException("ReqWS sync coordinator disposed"))
  }

  internal suspend fun awaitClosed() {
    worker.join()
  }
}

private sealed interface Submission<T> {
  val requestId: Long
  val trigger: SyncTrigger
}

private data class CandidateSubmission<T>(
  override val requestId: Long,
  override val trigger: SyncTrigger,
  val candidate: SyncCandidate<T>,
) : Submission<T>

private data class ReadFailureSubmission<T>(
  override val requestId: Long,
  override val trigger: SyncTrigger,
  val digestSha256: String?,
  val cause: Throwable,
) : Submission<T>

private fun <T> Submission<T>.withTrigger(trigger: SyncTrigger): Submission<T> = when (this) {
  is CandidateSubmission -> copy(trigger = trigger)
  is ReadFailureSubmission -> copy(trigger = trigger)
}
