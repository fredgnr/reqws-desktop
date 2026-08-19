package com.reqws.goland.project

import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

internal fun interface TrustStateProbe {
  fun isTrusted(): Boolean
}

internal fun interface TrustPollWaiter {
  suspend fun await(delayMillis: Long)
}

internal fun interface TrustedTransitionAction {
  suspend fun run()
}

/**
 * Detects a Safe Mode -> trusted transition without depending on the experimental
 * TrustedProjectsListener API. The monitor only runs while a valid ReqWS project
 * is blocked, and stops after the first observed trusted state.
 */
internal class TrustTransitionMonitor(
  private val scope: CoroutineScope,
  private val probe: TrustStateProbe,
  private val action: TrustedTransitionAction,
  private val pollMillis: Long = DEFAULT_POLL_MILLIS,
  private val waiter: TrustPollWaiter = TrustPollWaiter { delay(it) },
  private val onFailure: (Throwable) -> Unit = {},
) : AutoCloseable {
  private val parentJob = requireNotNull(scope.coroutineContext[Job]) {
    "TrustTransitionMonitor requires a lifecycle-owned CoroutineScope"
  }
  private val closed = AtomicBoolean(false)
  private val lock = Any()
  private var pollingJob: Job? = null
  private var transitionActionRunning = false
  private var rearmAfterAction = false

  init {
    require(pollMillis >= MIN_POLL_MILLIS) {
      "Trust polling must not run more frequently than every $MIN_POLL_MILLIS milliseconds"
    }
  }

  fun awaitTrusted(): Boolean = synchronized(lock) {
    if (closed.get() || !parentJob.isActive) return false
    if (pollingJob?.isActive == true) {
      // A load started by the observed transition can become blocked again before this polling
      // job's completion callback clears it. Remember that request so the next transition is not
      // lost in the narrow action -> completion window.
      if (transitionActionRunning) rearmAfterAction = true
      return true
    }

    startPollingLocked()
    true
  }

  private fun startPollingLocked() {
    transitionActionRunning = false
    rearmAfterAction = false
    lateinit var job: Job
    job = scope.launch(start = CoroutineStart.LAZY) {
      try {
        while (!closed.get()) {
          if (probe.isTrusted()) {
            val mayRun = synchronized(lock) {
              if (pollingJob !== job || closed.get()) {
                false
              } else {
                transitionActionRunning = true
                true
              }
            }
            if (!mayRun) return@launch
            action.run()
            return@launch
          }
          waiter.await(pollMillis)
        }
      } catch (exception: CancellationException) {
        throw exception
      } catch (exception: Exception) {
        try {
          onFailure(exception)
        } catch (_: Exception) {
          // Monitoring diagnostics must not escape the project scope.
        }
      }
    }
    pollingJob = job
    job.invokeOnCompletion {
      val shouldRearm = synchronized(lock) {
        if (pollingJob !== job) {
          false
        } else {
          pollingJob = null
          transitionActionRunning = false
          val requested = rearmAfterAction
          rearmAfterAction = false
          requested && !closed.get() && parentJob.isActive
        }
      }
      if (shouldRearm) awaitTrusted()
    }
    job.start()
  }

  fun cancelPending() {
    synchronized(lock) {
      val job = pollingJob
      pollingJob = null
      transitionActionRunning = false
      rearmAfterAction = false
      job?.cancel()
    }
  }

  override fun close() {
    synchronized(lock) {
      if (!closed.compareAndSet(false, true)) return
      val job = pollingJob
      pollingJob = null
      transitionActionRunning = false
      rearmAfterAction = false
      job?.cancel()
    }
  }

  companion object {
    const val DEFAULT_POLL_MILLIS = 1_000L
    const val MIN_POLL_MILLIS = 250L
  }
}
