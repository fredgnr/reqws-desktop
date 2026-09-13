package com.reqws.goland.diagnostics

import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext
import kotlinx.coroutines.asContextElement

/** Fixed identifiers keep this opt-in channel independent of all manifest and exception text. */
internal enum class SyncTraceEvent {
  SERVICE_STARTED,
  SERVICE_DISPOSE_START,
  SERVICE_DISPOSE_END,
  WATCHER_STARTED,
  WATCH_REFRESH_START,
  WATCH_REFRESH_END,
  VFS_BATCH,
  WATCH_DISPATCH,
  WATCHER_DISPOSED,
  ROOTS_RECEIVED,
  ROOTS_DECISION,
  ROOTS_DEBOUNCE_DISPATCH,
  READ_START,
  READ_END,
  COORDINATOR_SUBMIT,
  COORDINATOR_DEQUEUE,
  COORDINATOR_APPLY_START,
  COORDINATOR_APPLY_END,
  COORDINATOR_NO_OP,
  COORDINATOR_READ_FAILED,
  COORDINATOR_CLOSED,
  PROJECTION_STAGE_START,
  PROJECTION_STAGE_END,
  REGISTRY_START,
  REGISTRY_END,
  ROOTS_NOTIFICATION,
}

internal enum class SyncTraceField {
  REQUEST_ID,
  SOURCE_ID,
  SPAN_ID,
  TRIGGER,
  REPLACED_REQUEST_ID,
  COUNT,
  RECEIVED_COUNT,
  MATCHED_COUNT,
  EVENT_EPOCH,
  KIND,
  GUARDED,
  ACCEPTED,
  REASON,
  TARGET_SCOPE,
  LIFECYCLE,
  STAGE,
  OUTCOME,
  ELAPSED_NANOS,
  READS,
  WAITS,
  NOTIFICATIONS,
  NOTIFICATION_ATTEMPTS,
  NOTIFICATION_REJECTED,
  ALLOW_NOTIFICATION,
  ACTIVE_COUNT,
  EXCLUDED_COUNT;

  operator fun invoke(value: Long): SyncTraceAttribute =
    SyncTraceAttribute.number(this, value)

  operator fun invoke(value: Int): SyncTraceAttribute = invoke(value.toLong())

  operator fun invoke(value: Boolean): SyncTraceAttribute = invoke(if (value) 1L else 0L)

  operator fun invoke(value: Enum<*>): SyncTraceAttribute =
    SyncTraceAttribute.code(this, value)
}

/** Construct attributes only through the numeric, boolean, and enum overloads above. */
internal class SyncTraceAttribute private constructor(
  val field: SyncTraceField,
  val value: String,
) {
  companion object {
    fun number(field: SyncTraceField, value: Long): SyncTraceAttribute =
      SyncTraceAttribute(field, value.toString())

    fun code(field: SyncTraceField, value: Enum<*>): SyncTraceAttribute =
      SyncTraceAttribute(field, value.name)
  }
}

private data class SyncTraceAttempt(val requestId: Long, val sourceId: Long?)

private class SyncTraceAttemptHolder {
  @Volatile var value: SyncTraceAttempt? = null
}

/**
 * Project-local, default-off measurement of existing sync work. It never opens a file, schedules
 * work, or observes a platform model. A failed sink cannot alter the real operation being traced.
 */
@Service(Service.Level.PROJECT)
internal class ReqwsSyncTrace private constructor(
  val enabled: Boolean,
  private val serviceId: Long,
  private val clock: () -> Long,
  private val sink: (String) -> Unit,
) {
  constructor() : this(
    enabled = System.getProperty(PROPERTY_NAME) == "true",
    serviceId = nextServiceId.incrementAndGet(),
    clock = System::nanoTime,
    sink = { LOG.info(it) },
  )

  private val originNanos = if (enabled) clock() else 0L
  private val sequence = AtomicLong(0)
  private val spanSequence = AtomicLong(0)
  private val attempt = ThreadLocal<SyncTraceAttemptHolder?>()

  fun nextSpanId(): Long = if (enabled) spanSequence.incrementAndGet() else 0L

  fun nanoTime(): Long = if (enabled) clock() else 0L

  fun elapsedNanos(start: Long): Long = if (enabled) (clock() - start).coerceAtLeast(0L) else 0L

  /** The caller guards field construction with [enabled]; this guard also protects direct calls. */
  fun record(event: SyncTraceEvent, vararg attributes: SyncTraceAttribute) {
    if (!enabled) return
    try {
      val current = attempt.get()?.value
      val fields = linkedMapOf(
        SyncTraceField.REQUEST_ID to (current?.requestId ?: 0L).toString(),
        SyncTraceField.SOURCE_ID to (current?.sourceId ?: 0L).toString(),
      )
      attributes.forEach { fields[it.field] = it.value }
      val line = buildString {
        append(PREFIX)
        append(" schema=1 service_id=").append(serviceId)
        append(" seq=").append(sequence.incrementAndGet())
        append(" mono_ns=").append((clock() - originNanos).coerceAtLeast(0L))
        append(" event=").append(event.name)
        fields.forEach { (field, value) ->
          append(' ').append(field.name.lowercase(Locale.ROOT)).append('=').append(value)
        }
      }
      sink(line)
    } catch (_: Exception) {
      // Only this diagnostic sink/formatter is isolated. Never catch the operation being traced.
    }
  }

  /** Each existing worker owns one holder; installing it adds no coroutine or Job. */
  fun workerContext(): CoroutineContext = if (enabled) {
    attempt.asContextElement(SyncTraceAttemptHolder())
  } else {
    EmptyCoroutineContext
  }

  /** The serial worker scopes identity without changing its Job or exception propagation. */
  suspend fun <T> withAttempt(
    requestId: Long,
    sourceId: Long?,
    block: suspend () -> T,
  ): T {
    if (!enabled) return block()
    val holder = attempt.get() ?: return block()
    val previous = holder.value
    holder.value = SyncTraceAttempt(requestId, sourceId)
    try {
      return block()
    } finally {
      holder.value = previous
    }
  }

  companion object {
    const val PROPERTY_NAME = "reqws.sync.trace"
    const val PREFIX = "REQWS_SYNC_TRACE"
    private val nextServiceId = AtomicLong(System.nanoTime())
    private val LOG: Logger by lazy { Logger.getInstance(ReqwsSyncTrace::class.java) }
    val NONE = ReqwsSyncTrace(false, 0L, { 0L }, {})

    fun testing(
      enabled: Boolean = true,
      serviceId: Long = 1L,
      nanoTime: () -> Long = System::nanoTime,
      sink: (String) -> Unit,
    ): ReqwsSyncTrace = ReqwsSyncTrace(enabled, serviceId, nanoTime, sink)
  }
}
