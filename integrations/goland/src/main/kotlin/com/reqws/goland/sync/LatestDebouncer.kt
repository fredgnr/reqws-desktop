package com.reqws.goland.sync

import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal fun interface DebounceWaiter {
  suspend fun await(delayMillis: Long)
}

internal fun interface DebouncedAction<T> {
  suspend fun run(value: T)
}

/** A lifecycle-owned, resettable debounce that executes at most one callback at a time. */
internal class LatestDebouncer<T>(
  private val scope: CoroutineScope,
  val delayMillis: Long = DEFAULT_DELAY_MILLIS,
  private val waiter: DebounceWaiter = DebounceWaiter { delay(it) },
  private val action: DebouncedAction<T>,
  private val onFailure: (Throwable) -> Unit = {},
) : AutoCloseable {
  private val parentJob = requireNotNull(scope.coroutineContext[Job]) {
    "LatestDebouncer requires a lifecycle-owned CoroutineScope"
  }
  private val closed = AtomicBoolean(false)
  private val generation = AtomicLong(0)
  private val actionMutex = Mutex()
  private val lock = Any()
  private var pendingJob: Job? = null

  init {
    require(delayMillis in MIN_DELAY_MILLIS..MAX_DELAY_MILLIS) {
      "Debounce delay must be between $MIN_DELAY_MILLIS and $MAX_DELAY_MILLIS milliseconds"
    }
  }

  val isClosed: Boolean
    get() = closed.get() || !parentJob.isActive

  fun submit(value: T): Boolean = schedule(value, waitBeforeAction = true)

  /** Cancels any delayed callback and submits immediately through the same serialized action. */
  fun submitNow(value: T): Boolean = schedule(value, waitBeforeAction = false)

  private fun schedule(value: T, waitBeforeAction: Boolean): Boolean = synchronized(lock) {
    if (closed.get() || !parentJob.isActive) return false

    val submittedGeneration = generation.incrementAndGet()
    pendingJob?.cancel()
    val job = scope.launch {
      try {
        if (waitBeforeAction) waiter.await(delayMillis)
        actionMutex.withLock {
          currentCoroutineContext().ensureActive()
          if (closed.get() || generation.get() != submittedGeneration) return@withLock
          action.run(value)
        }
      } catch (exception: CancellationException) {
        throw exception
      } catch (exception: Exception) {
        notifyFailure(exception)
      }
    }
    pendingJob = job
    job.invokeOnCompletion {
      synchronized(lock) {
        if (pendingJob === job) pendingJob = null
      }
    }
    true
  }

  private fun notifyFailure(exception: Throwable) {
    try {
      onFailure(exception)
    } catch (_: Exception) {
      // A reporting failure must not make future debounce submissions unusable.
    }
  }

  override fun close() {
    synchronized(lock) {
      if (!closed.compareAndSet(false, true)) return
      generation.incrementAndGet()
      pendingJob?.cancel()
      pendingJob = null
    }
  }

  companion object {
    const val DEFAULT_DELAY_MILLIS = 350L
    const val MIN_DELAY_MILLIS = 250L
    const val MAX_DELAY_MILLIS = 500L
  }
}
