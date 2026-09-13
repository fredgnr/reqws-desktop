package com.reqws.goland.diagnostics

import com.intellij.openapi.progress.ProcessCanceledException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

internal fun traceRecords(lines: List<String>, event: SyncTraceEvent): List<Map<String, String>> =
  lines.map { line ->
    check(line.startsWith("REQWS_SYNC_TRACE "))
    line.substringAfter(' ').split(' ').associate { token ->
      token.substringBefore('=') to token.substringAfter('=')
    }
  }.filter { it["event"] == event.name }

class ReqwsSyncTraceTest {
  @Test
  fun `disabled trace does no clock formatting or sink work`() = runBlocking {
    val clockCalls = AtomicLong()
    val sinkCalls = AtomicLong()
    val trace = ReqwsSyncTrace.testing(
      enabled = false,
      nanoTime = { clockCalls.incrementAndGet() },
      sink = { sinkCalls.incrementAndGet() },
    )
    assertEquals(0L, trace.nextSpanId())
    assertEquals(0L, trace.nanoTime())
    assertEquals(0L, trace.elapsedNanos(1L))
    // The formatter reads the clock; counters also detect work hidden by record's exception guard.
    trace.record(SyncTraceEvent.SERVICE_STARTED)
    val owner = currentCoroutineContext()[Job]
    trace.withAttempt(1, 2) { assertSame(owner, currentCoroutineContext()[Job]) }
    assertEquals(0L, clockCalls.get())
    assertEquals(0L, sinkCalls.get())
  }

  @Test
  fun `records only numeric fields and fixed enum names without invoking enum toString`() {
    val clock = AtomicLong(10)
    val lines = mutableListOf<String>()
    val trace = ReqwsSyncTrace.testing(serviceId = 7, nanoTime = clock::getAndIncrement, sink = lines::add)
    trace.record(
      SyncTraceEvent.SERVICE_STARTED,
      SyncTraceField.OUTCOME(SecretEnum.SAFE), SyncTraceField.ACCEPTED(true),
    )
    trace.record(
      SyncTraceEvent.SERVICE_DISPOSE_END,
      SyncTraceField.COUNT(3), SyncTraceField.ACCEPTED(false),
    )
    val first = traceRecords(lines, SyncTraceEvent.SERVICE_STARTED).single()
    assertEquals("SAFE", first["outcome"])
    assertEquals("7", first["service_id"])
    assertEquals("1", first["seq"])
    assertEquals("1", first["mono_ns"])
    assertEquals("0", first["request_id"])
    assertEquals("0", first["source_id"])
    assertEquals("1", first["accepted"])
    val second = traceRecords(lines, SyncTraceEvent.SERVICE_DISPOSE_END).single()
    assertEquals("2", second["seq"])
    assertEquals("2", second["mono_ns"])
    assertEquals("0", second["accepted"])
    val allowedRecord = Regex("REQWS_SYNC_TRACE(?: [a-z_]+=(?:-?[0-9]+|[A-Z][A-Z_0-9]*))+")
    assertTrue(lines.all { it.matches(allowedRecord) })
  }

  @Test
  fun `attempt identity follows dispatchers isolates concurrent work and restores nesting`() = runBlocking {
    val lines = CopyOnWriteArrayList<String>()
    val trace = ReqwsSyncTrace.testing(sink = lines::add)
    val firstEntered = CompletableDeferred<Unit>()
    val secondEntered = CompletableDeferred<Unit>()
    withTimeout(5_000) {
      val first = async(Dispatchers.Default + trace.workerContext()) {
        trace.withAttempt(11, 101) {
          firstEntered.complete(Unit)
          secondEntered.await()
          withContext(Dispatchers.IO) { trace.record(SyncTraceEvent.READ_START) }
          trace.withAttempt(12, 102) { trace.record(SyncTraceEvent.READ_END) }
          trace.record(SyncTraceEvent.COORDINATOR_APPLY_END)
        }
        trace.record(SyncTraceEvent.SERVICE_DISPOSE_END)
      }
      val second = async(Dispatchers.Default + trace.workerContext()) {
        trace.withAttempt(21, 201) {
          firstEntered.await()
          secondEntered.complete(Unit)
          withContext(Dispatchers.IO) { trace.record(SyncTraceEvent.READ_START) }
        }
      }
      first.await()
      second.await()
    }
    assertEquals(
      setOf("11" to "101", "21" to "201"),
      traceRecords(lines, SyncTraceEvent.READ_START).map {
        it["request_id"] to it["source_id"]
      }.toSet(),
    )
    assertEquals("12", traceRecords(lines, SyncTraceEvent.READ_END).single()["request_id"])
    assertEquals("11", traceRecords(lines, SyncTraceEvent.COORDINATOR_APPLY_END).single()["request_id"])
    assertEquals("0", traceRecords(lines, SyncTraceEvent.SERVICE_DISPOSE_END).single()["request_id"])
  }

  @Test
  fun `sink failures do not replace operation exceptions and attempt context is cleared`() = runBlocking {
    val sinkFailures = listOf(
      IllegalStateException("sink"), ProcessCanceledException(), CancellationException("sink"),
    )
    for (sinkFailure in sinkFailures) {
      val lines = mutableListOf<String>()
      val trace = ReqwsSyncTrace.testing(sink = { lines += it; throw sinkFailure })
      withContext(trace.workerContext()) {
        val owner = currentCoroutineContext()[Job]
        val operationFailures = listOf(
          IllegalStateException("operation"), ProcessCanceledException(), CancellationException("operation"),
        )
        for (operationFailure in operationFailures) {
          var caught: Throwable? = null
          try {
            trace.withAttempt(7, 8) {
              assertSame(owner, currentCoroutineContext()[Job])
              trace.record(SyncTraceEvent.READ_START)
              throw operationFailure
            }
          } catch (failure: Throwable) {
            caught = failure
          }
          assertSame(operationFailure, caught)
          trace.record(SyncTraceEvent.READ_END)
        }
      }
      assertTrue(traceRecords(lines, SyncTraceEvent.READ_END).all {
        it["request_id"] == "0" && it["source_id"] == "0"
      })
    }
  }

  @Test
  fun `attempt without installed worker context leaves the caller and exception untouched`() = runBlocking {
    val lines = mutableListOf<String>()
    val trace = ReqwsSyncTrace.testing(sink = lines::add)
    val owner = currentCoroutineContext()[Job]
    val value = trace.withAttempt(3, 4) {
      assertSame(owner, currentCoroutineContext()[Job])
      trace.record(SyncTraceEvent.READ_START)
      "value"
    }
    assertEquals("value", value)
    assertEquals("0", traceRecords(lines, SyncTraceEvent.READ_START).single()["request_id"])
  }

  private enum class SecretEnum {
    SAFE;
    override fun toString(): String = error("must not format arbitrary text")
  }
}
