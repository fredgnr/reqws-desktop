package com.reqws.goland.project

import com.intellij.openapi.components.service
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

class ReqwsProjectServiceTest : BasePlatformTestCase() {
  fun testInitializesCallbackDependenciesBeforeRegisteringForVcsChanges() =
    verifyInitializesCallbackDependenciesBeforeRegisteringForVcsChanges()

  private fun verifyInitializesCallbackDependenciesBeforeRegisteringForVcsChanges() {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val callbackCompleted = AtomicBoolean(false)
    val registrationCount = AtomicInteger(0)
    val registrationClosed = AtomicBoolean(false)
    val registrar = ReqwsVcsChangeRegistrar { listener ->
      registrationCount.incrementAndGet()
      awaitSuccessfulCompletion(
        job = requireNotNull(listener()),
        description = "registration-time VCS refresh",
      )
      callbackCompleted.set(true)
      AutoCloseable { registrationClosed.set(true) }
    }

    val service = ReqwsProjectService(project, scope)
    try {
      assertTrue(service.startVcsChangeMonitoring(registrar))
      assertTrue(callbackCompleted.get())
      assertEquals(1, registrationCount.get())
    } finally {
      service.dispose()
      scope.cancel()
    }
    assertTrue(registrationClosed.get())
  }

  fun testConcurrentRefreshWaitsForVcsChangeRegistration() =
    verifyConcurrentRefreshWaitsForVcsChangeRegistration()

  private fun verifyConcurrentRefreshWaitsForVcsChangeRegistration() {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val service = ReqwsProjectService(project, scope)
    val registrationEntered = CountDownLatch(1)
    val allowRegistration = CountDownLatch(1)
    val startFinished = CountDownLatch(1)
    val refreshAttempted = CountDownLatch(1)
    val refreshReturned = CountDownLatch(1)
    val registrationCount = AtomicInteger(0)
    val startResult = AtomicReference<Boolean?>()
    val refreshJob = AtomicReference<Job?>()
    val startFailure = AtomicReference<Throwable?>()
    val refreshFailure = AtomicReference<Throwable?>()
    val registrar = ReqwsVcsChangeRegistrar {
      registrationCount.incrementAndGet()
      registrationEntered.countDown()
      check(allowRegistration.await(5, TimeUnit.SECONDS)) {
        "test did not release VCS registration"
      }
      AutoCloseable {}
    }
    val startThread = Thread({
      try {
        startResult.set(service.startVcsChangeMonitoring(registrar))
      } catch (failure: Throwable) {
        startFailure.set(failure)
      } finally {
        startFinished.countDown()
      }
    }, "reqws-vcs-start")
    val refreshThread = Thread({
      refreshAttempted.countDown()
      try {
        refreshJob.set(service.refreshAutomatically())
      } catch (failure: Throwable) {
        refreshFailure.set(failure)
      } finally {
        refreshReturned.countDown()
      }
    }, "reqws-vcs-refresh")

    try {
      startThread.start()
      assertTrue(registrationEntered.await(5, TimeUnit.SECONDS))
      refreshThread.start()
      assertTrue(refreshAttempted.await(5, TimeUnit.SECONDS))
      assertTrue(
        "concurrent refresh did not block on VCS registration",
        awaitThreadState(refreshThread, Thread.State.BLOCKED),
      )
      assertEquals(1L, refreshReturned.count)
      assertEquals(ReqwsLifecycleState.INACTIVE, service.state.lifecycle)

      allowRegistration.countDown()
      assertTrue(startFinished.await(5, TimeUnit.SECONDS))
      assertTrue(refreshReturned.await(5, TimeUnit.SECONDS))
      startFailure.get()?.let { throw AssertionError("VCS start failed", it) }
      refreshFailure.get()?.let { throw AssertionError("concurrent refresh failed", it) }
      assertEquals(true, startResult.get())
      awaitSuccessfulCompletion(
        job = requireNotNull(refreshJob.get()),
        description = "refresh after VCS registration",
      )
      assertEquals(1, registrationCount.get())
    } finally {
      allowRegistration.countDown()
      startThread.join(5_000)
      refreshThread.join(5_000)
      service.dispose()
      scope.cancel()
    }
    assertFalse(startThread.isAlive)
    assertFalse(refreshThread.isAlive)
  }

  fun testDisposeClosesRegistrationThatReturnsAfterTerminalState() =
    verifyDisposeClosesRegistrationThatReturnsAfterTerminalState()

  private fun verifyDisposeClosesRegistrationThatReturnsAfterTerminalState() {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val service = ReqwsProjectService(project, scope)
    val registrationEntered = CountDownLatch(1)
    val allowRegistration = CountDownLatch(1)
    val disposedPublished = CountDownLatch(1)
    val startFinished = CountDownLatch(1)
    val disposeFinished = CountDownLatch(1)
    val closeCount = AtomicInteger(0)
    val startResult = AtomicReference<Boolean?>()
    val startFailure = AtomicReference<Throwable?>()
    val disposeFailure = AtomicReference<Throwable?>()
    val stateHandle = service.addListener { state ->
      if (state.lifecycle == ReqwsLifecycleState.DISPOSED) disposedPublished.countDown()
    }
    val registrar = ReqwsVcsChangeRegistrar {
      registrationEntered.countDown()
      check(allowRegistration.await(5, TimeUnit.SECONDS)) {
        "test did not release VCS registration"
      }
      AutoCloseable { closeCount.incrementAndGet() }
    }
    val startThread = Thread({
      try {
        startResult.set(service.startVcsChangeMonitoring(registrar))
      } catch (failure: Throwable) {
        startFailure.set(failure)
      } finally {
        startFinished.countDown()
      }
    }, "reqws-vcs-start-before-dispose")
    val disposeThread = Thread({
      try {
        service.dispose()
      } catch (failure: Throwable) {
        disposeFailure.set(failure)
      } finally {
        disposeFinished.countDown()
      }
    }, "reqws-dispose-during-vcs-start")

    try {
      startThread.start()
      assertTrue(registrationEntered.await(5, TimeUnit.SECONDS))
      disposeThread.start()
      assertTrue(disposedPublished.await(5, TimeUnit.SECONDS))
      assertTrue(
        "dispose did not wait for the in-flight VCS registration",
        awaitThreadState(disposeThread, Thread.State.BLOCKED),
      )
      assertEquals(1L, disposeFinished.count)

      allowRegistration.countDown()
      assertTrue(startFinished.await(5, TimeUnit.SECONDS))
      assertTrue(disposeFinished.await(5, TimeUnit.SECONDS))
      startFailure.get()?.let { throw AssertionError("VCS start failed", it) }
      disposeFailure.get()?.let { throw AssertionError("project dispose failed", it) }
      assertEquals(false, startResult.get())
      assertEquals(1, closeCount.get())
      assertSame(ReqwsProjectState.DISPOSED, service.state)
      assertNull(service.refreshAutomatically())
    } finally {
      allowRegistration.countDown()
      startThread.join(5_000)
      disposeThread.join(5_000)
      service.dispose()
      stateHandle.close()
      scope.cancel()
    }
    assertFalse(startThread.isAlive)
    assertFalse(disposeThread.isAlive)
  }

  fun testDisposePublishesTerminalStateAndMakesRefreshANoOp() =
    verifyDisposePublishesTerminalStateAndMakesRefreshANoOp()

  private fun verifyDisposePublishesTerminalStateAndMakesRefreshANoOp() {
    val service = project.service<ReqwsProjectService>()
    val observed = mutableListOf<ReqwsLifecycleState>()
    val handle = service.addListener { observed.add(it.lifecycle) }

    service.dispose()

    assertSame(ReqwsProjectState.DISPOSED, service.state)
    assertNull(service.refresh())
    assertNull(service.refreshAutomatically())
    assertEquals(ReqwsLifecycleState.INACTIVE, observed.first())
    assertEquals(ReqwsLifecycleState.DISPOSED, observed.last())
    handle.close()
  }

  private fun awaitSuccessfulCompletion(job: Job, description: String) {
    val completion = CountDownLatch(1)
    val failure = AtomicReference<Throwable?>()
    job.invokeOnCompletion { cause ->
      failure.set(cause)
      completion.countDown()
    }
    assertTrue("$description did not complete", completion.await(5, TimeUnit.SECONDS))
    failure.get()?.let { throw AssertionError("$description failed", it) }
  }

  private fun awaitThreadState(thread: Thread, expected: Thread.State): Boolean {
    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
    while (System.nanoTime() < deadline) {
      if (thread.state == expected) return true
      Thread.yield()
    }
    return thread.state == expected
  }
}
