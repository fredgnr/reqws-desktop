package com.reqws.goland.sync

import com.intellij.openapi.progress.ProcessCanceledException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch

internal enum class SyncTrigger {
  AUTOMATIC,
  PROJECT_MODEL_FOLLOW_UP,
  PROJECT_MODEL_CHANGE,
  TRUST_TRANSITION,
  MANUAL,
}

internal val SyncTrigger.requiresReconciliation: Boolean
  get() = this != SyncTrigger.AUTOMATIC

/** A verify-only language follow-up applies once but must not leak into a later read/digest. */
private val SyncTrigger.retainsCoordinatorReconcileIntent: Boolean
  get() = requiresReconciliation && this != SyncTrigger.PROJECT_MODEL_FOLLOW_UP

private val SyncTrigger.reconciliationPriority: Int
  get() = when (this) {
    SyncTrigger.AUTOMATIC -> 0
    SyncTrigger.PROJECT_MODEL_FOLLOW_UP -> 1
    SyncTrigger.PROJECT_MODEL_CHANGE -> 2
    SyncTrigger.TRUST_TRANSITION -> 3
    SyncTrigger.MANUAL -> 4
  }

/** Retains the strongest pending reconciliation intent while newer reads replace its bytes. */
internal fun mergeReconcileTrigger(
  current: SyncTrigger?,
  incoming: SyncTrigger,
): SyncTrigger? {
  if (!incoming.requiresReconciliation) return current
  if (current == null || !current.requiresReconciliation) return incoming
  return if (incoming.reconciliationPriority > current.reconciliationPriority) {
    incoming
  } else {
    current
  }
}

internal data class SyncCandidate<T>(
  val digestSha256: String,
  val value: T,
  val trigger: SyncTrigger = SyncTrigger.AUTOMATIC,
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

  data class Cancelled(
    override val requestId: Long,
    override val trigger: SyncTrigger,
    override val digestSha256: String,
    val cause: Throwable,
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

internal fun interface SyncCandidateCommitter<T> {
  fun commit(candidate: SyncCandidate<T>)
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
  private val committer: SyncCandidateCommitter<T> = SyncCandidateCommitter {},
  private val observer: SyncCoordinatorObserver = SyncCoordinatorObserver {},
) : AutoCloseable {
  private val closed = AtomicBoolean(false)
  private val nextRequestId = AtomicLong(0)
  private val appliedDigest = AtomicReference<String?>(initialAppliedDigest)
  private val submissionLock = Any()
  private var pendingSubmission: Submission<T>? = null
  private var pendingReconcileTrigger: SyncTrigger? = null
  private var pendingFollowUpDigest: String? = null
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
        pendingReconcileTrigger = null
        pendingFollowUpDigest = null
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
      if (submission.trigger.retainsCoordinatorReconcileIntent) {
        pendingReconcileTrigger = mergeReconcileTrigger(
          pendingReconcileTrigger,
          submission.trigger,
        )
        pendingFollowUpDigest = null
      }
      val retainedTrigger = pendingReconcileTrigger
      pendingSubmission = when (submission) {
        is CandidateSubmission -> {
          val digest = submission.candidate.digestSha256
          val effectiveTrigger = when {
            retainedTrigger != null -> retainedTrigger
            submission.trigger == SyncTrigger.PROJECT_MODEL_FOLLOW_UP -> {
              pendingFollowUpDigest = digest
              SyncTrigger.PROJECT_MODEL_FOLLOW_UP
            }
            pendingFollowUpDigest == digest -> SyncTrigger.PROJECT_MODEL_FOLLOW_UP
            else -> {
              // A different manifest supersedes the verify-only event lineage and must retain its
              // ordinary roots-notification permission.
              pendingFollowUpDigest = null
              submission.trigger
            }
          }
          submission.withTrigger(effectiveTrigger)
        }
        is ReadFailureSubmission -> submission.withTrigger(
          retainedTrigger ?: submission.trigger,
        )
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
        if (next is CandidateSubmission && next.trigger.requiresReconciliation) {
          // Forced intents are consumed only when a valid candidate actually starts apply. A read
          // failure keeps the strongest intent sticky so the next valid automatic read still
          // reconciles.
          pendingReconcileTrigger = null
        }
        if (
          next is CandidateSubmission &&
          next.trigger == SyncTrigger.PROJECT_MODEL_FOLLOW_UP &&
          pendingFollowUpDigest == next.candidate.digestSha256
        ) {
          // The coordinator now owns the accepted verify-only lineage. Consume it only when the
          // candidate actually leaves the pending slot and starts its bounded replay.
          pendingFollowUpDigest = null
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
    val candidate = submission.candidate.copy(trigger = submission.trigger)
    // Manual refresh, Safe Mode -> trusted transition, and external project-model changes are
    // explicit reconciliation requests: the live model may drift while manifest bytes stay fixed.
    if (
      !submission.trigger.requiresReconciliation &&
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
      // This is the accepted-success linearization boundary. Cancellation observed before it
      // prevents durable/in-memory digest advancement; cancellation arriving after the final gate
      // is ordered after the synchronous commit and cannot turn the accepted apply into Cancelled.
      currentCoroutineContext().ensureActive()
      committer.commit(candidate)
      appliedDigest.set(candidate.digestSha256)
      notifyObserver(
        SyncCoordinatorEvent.Applied(
          requestId = submission.requestId,
          trigger = submission.trigger,
          digestSha256 = candidate.digestSha256,
        ),
      )
    } catch (exception: ProcessCanceledException) {
      notifySubmissionCancelled(submission, exception)
    } catch (exception: CancellationException) {
      notifySubmissionCancelled(submission, exception)
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

  /**
   * Keeps a platform cancellation scoped to the candidate that observed it. A lifecycle-owned
   * cancellation makes the worker context inactive and must still close the coordinator; a
   * cancellation thrown by an otherwise-active applier leaves the dirty digest baseline in place
   * and lets the next submission reconcile again.
   */
  private suspend fun notifySubmissionCancelled(
    submission: CandidateSubmission<T>,
    cause: Throwable,
  ) {
    currentCoroutineContext().ensureActive()
    notifyObserver(
      SyncCoordinatorEvent.Cancelled(
        requestId = submission.requestId,
        trigger = submission.trigger,
        digestSha256 = submission.candidate.digestSha256,
        cause = cause,
      ),
    )
  }

  private fun notifyObserver(event: SyncCoordinatorEvent) {
    try {
      observer.onEvent(event)
    } catch (exception: ProcessCanceledException) {
      throw exception
    } catch (exception: CancellationException) {
      throw exception
    } catch (_: Exception) {
      // Observability must never terminate the synchronization worker.
    }
  }

  override fun close() {
    if (!closed.compareAndSet(false, true)) return
    synchronized(submissionLock) {
      pendingSubmission = null
      pendingReconcileTrigger = null
      pendingFollowUpDigest = null
    }
    submissionSignal.close()
    worker.cancel(CancellationException("ReqWS sync coordinator disposed"))
  }

  internal suspend fun awaitClosed(): Throwable? {
    val completion = CompletableDeferred<Throwable?>()
    worker.invokeOnCompletion { cause -> completion.complete(cause) }
    return completion.await()
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
