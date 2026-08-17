package com.reqws.goland.project

import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
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

  init {
    require(pollMillis >= MIN_POLL_MILLIS) {
      "Trust polling must not run more frequently than every $MIN_POLL_MILLIS milliseconds"
    }
  }

  fun awaitTrusted(): Boolean = synchronized(lock) {
    if (closed.get() || !parentJob.isActive) return false
    if (pollingJob?.isActive == true) return true

    val job = scope.launch {
      try {
        while (!closed.get()) {
          if (probe.isTrusted()) {
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
      synchronized(lock) {
        if (pollingJob === job) pollingJob = null
      }
    }
    true
  }

  fun cancelPending() {
    synchronized(lock) {
      pollingJob?.cancel()
      pollingJob = null
    }
  }

  override fun close() {
    synchronized(lock) {
      if (!closed.compareAndSet(false, true)) return
      pollingJob?.cancel()
      pollingJob = null
    }
  }

  companion object {
    const val DEFAULT_POLL_MILLIS = 1_000L
    const val MIN_POLL_MILLIS = 250L
  }
}
