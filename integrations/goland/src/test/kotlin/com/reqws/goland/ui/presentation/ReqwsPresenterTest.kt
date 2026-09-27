package com.reqws.goland.ui.presentation

import com.reqws.goland.project.ReqwsLifecycleState
import com.reqws.goland.project.ReqwsProjectError
import com.reqws.goland.project.ReqwsProjectState
import com.reqws.goland.project.TerminalStatePublisher
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test

class ReqwsPresenterTest {
  private fun publisher() = TerminalStatePublisher(ReqwsProjectState.INACTIVE) {
    it.lifecycle == ReqwsLifecycleState.DISPOSED
  }
  private fun presenter(duration: Long = 2_500) = ReqwsPresenter(
    CoroutineScope(SupervisorJob() + Dispatchers.Unconfined), duration,
  )

  @Test
  fun `subscription receives ordered initial and reentrant updates without an independent read`() {
    val publisher = publisher()
    publisher.addListener { if (it.lifecycle == ReqwsLifecycleState.READING) {
      publisher.publish(ReqwsProjectState(ReqwsLifecycleState.ERROR))
    } }
    presenter().use { presenter ->
      presenter.bind(publisher::addListener)
      publisher.publish(ReqwsProjectState(ReqwsLifecycleState.READING))
      assertEquals(ReqwsLifecycleState.ERROR, presenter.state.value.lifecycle)
    }
  }

  @Test
  fun `updates during registration cannot be overwritten by an old initial read`() {
    presenter().use { presenter ->
      presenter.bind { listener ->
        listener(ReqwsProjectState.INACTIVE)
        listener(ReqwsProjectState(ReqwsLifecycleState.READING))
        listener(ReqwsProjectState(ReqwsLifecycleState.ERROR))
        AutoCloseable { }
      }
      assertEquals(ReqwsLifecycleState.ERROR, presenter.state.value.lifecycle)
    }
  }

  @Test
  fun `rapid ordered updates map synchronously with no stale queued results`() {
    val publisher = publisher()
    presenter().use { presenter ->
      presenter.bind(publisher::addListener)
      repeat(1_000) { publisher.publish(ReqwsProjectState(ReqwsLifecycleState.ERROR,
        lastError = ReqwsProjectError("error-$it"))) }
      assertEquals("error-999", presenter.state.value.errorCode)
    }
  }

  @Test
  fun `duplicate binding is ignored and close releases exactly once`() {
    val adds = AtomicInteger()
    val closes = AtomicInteger()
    val presenter = presenter()
    val subscribe: ((ReqwsProjectState) -> Unit) -> AutoCloseable = {
      adds.incrementAndGet()
      AutoCloseable { closes.incrementAndGet() }
    }
    presenter.bind(subscribe)
    presenter.bind(subscribe)
    presenter.close()
    presenter.close()
    presenter.bind(subscribe)
    assertEquals(1, adds.get())
    assertEquals(1, closes.get())
  }

  @Test
  fun `close during registration closes the late handle and rejects further delivery`() {
    val entered = CountDownLatch(1)
    val release = CountDownLatch(1)
    val closes = AtomicInteger()
    val presenter = presenter()
    val worker = thread {
      presenter.bind { listener ->
        entered.countDown()
        check(release.await(5, TimeUnit.SECONDS))
        listener(ReqwsProjectState(ReqwsLifecycleState.ERROR))
        AutoCloseable { closes.incrementAndGet() }
      }
    }
    try {
      assertTrue(entered.await(5, TimeUnit.SECONDS))
      presenter.close()
    } finally {
      release.countDown()
      worker.join(5_000)
    }
    assertFalse(worker.isAlive)
    assertEquals(1, closes.get())
    assertEquals(ReqwsLifecycleState.INACTIVE, presenter.state.value.lifecycle)
  }

  @Test
  fun `terminal initial notification closes late registration and cannot resurrect`() {
    val closes = AtomicInteger()
    presenter().use { presenter ->
      presenter.bind { listener ->
        listener(ReqwsProjectState.DISPOSED)
        listener(ReqwsProjectState(ReqwsLifecycleState.ERROR))
        AutoCloseable { closes.incrementAndGet() }
      }
      assertEquals(ReqwsLifecycleState.DISPOSED, presenter.state.value.lifecycle)
      assertFalse(presenter.state.value.syncEnabled)
      assertEquals(1, closes.get())
    }
    assertEquals(1, closes.get())
  }

  @Test
  fun `copy feedback preserves errors resets on repeat and expires`() = runBlocking {
    val publisher = publisher()
    val error = ReqwsProjectState(ReqwsLifecycleState.ERROR, lastError = ReqwsProjectError("error"))
    publisher.publish(error)
    presenter(80).use { presenter ->
      presenter.bind(publisher::addListener)
      repeat(3) { presenter.diagnosticsCopied(error) }
      assertTrue(presenter.state.value.diagnosticsCopied)
      assertEquals("error", presenter.state.value.errorCode)
      withTimeout(5_000) { presenter.state.first { !it.diagnosticsCopied } }
      assertEquals("error", presenter.state.value.errorCode)
    }
  }

  @Test
  fun `new state clears feedback and late copy completion cannot acknowledge stale data`() {
    val publisher = publisher()
    val old = ReqwsProjectState(ReqwsLifecycleState.ERROR, lastError = ReqwsProjectError("old"))
    publisher.publish(old)
    presenter().use { presenter ->
      presenter.bind(publisher::addListener)
      presenter.diagnosticsCopied(old)
      val newer = old.copy(lastError = ReqwsProjectError("new"))
      publisher.publish(newer)
      presenter.diagnosticsCopied(old)
      assertFalse(presenter.state.value.diagnosticsCopied)
      assertEquals("new", presenter.state.value.errorCode)
    }
  }

  @Test
  fun `close cancels content feedback scope and prevents post disposal writes`() = runBlocking {
    val job = SupervisorJob()
    val presenter = ReqwsPresenter(CoroutineScope(job + Dispatchers.Unconfined), 10)
    val current = ReqwsProjectState(ReqwsLifecycleState.ERROR)
    var callback: ((ReqwsProjectState) -> Unit)? = null
    presenter.bind { callback = it; it(current); AutoCloseable { } }
    presenter.diagnosticsCopied(current)
    presenter.close()
    val last = presenter.state.value
    callback!!(ReqwsProjectState.DISPOSED)
    delay(30)
    assertTrue(job.isCancelled)
    assertSame(last, presenter.state.value)
  }
}
