package com.reqws.goland.loading

import com.reqws.goland.loading.model.InitialJpsWait
import com.reqws.goland.projectmodel.ProjectModelApplyException
import com.reqws.goland.projectmodel.ProjectModelErrorCode
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class InitialJpsWaitTest {
  @Test
  fun cancelledCandidateDoesNotCancelOrRestartTheSharedWait() = runBlocking {
    val scope = CoroutineScope(Job() + Dispatchers.Default)
    try {
      val entered = CompletableDeferred<Unit>()
      val release = CompletableDeferred<Unit>()
      val calls = AtomicInteger()
      val wait = InitialJpsWait(scope) { calls.incrementAndGet(); entered.complete(Unit); release.await() }
      val cancelled = launch { wait.await() }
      entered.await()
      cancelled.cancelAndJoin()
      val current = async { wait.await() }
      assertEquals(1, calls.get())
      release.complete(Unit)
      current.await()
      wait.await()
      assertEquals(1, calls.get())
    } finally { scope.cancel() }
  }

  @Test
  fun timeoutIsAStoredFailureAndNeverStartsAnotherPlatformWait() = runBlocking {
    val scope = CoroutineScope(Job() + Dispatchers.Default)
    try {
      val calls = AtomicInteger()
      val wait = InitialJpsWait(scope, timeoutMillis = 30) { calls.incrementAndGet(); awaitCancellation() }
      var previous: ProjectModelApplyException? = null
      repeat(2) {
        try { wait.await(); fail("A timeout must not release the model writer") }
        catch (failure: ProjectModelApplyException) {
          assertEquals(ProjectModelErrorCode.PROJECT_METADATA_NOT_READY, failure.code)
          if (previous != null) assertSame(previous, failure)
          previous = failure
        }
      }
      assertEquals(1, calls.get())
      assertTrue(scope.isActive)
    } finally { scope.cancel() }
  }

  @Test
  fun platformFailureIsRememberedWithoutCancellingTheServiceScope() = runBlocking {
    val scope = CoroutineScope(Job() + Dispatchers.Default)
    try {
      val failure = IllegalStateException("JPS fixture failure")
      val calls = AtomicInteger()
      val wait = InitialJpsWait(scope) { calls.incrementAndGet(); throw failure }
      repeat(2) {
        try { wait.await(); fail("A failed platform wait cannot allow mutation") }
        catch (actual: IllegalStateException) {
          // Coroutine stack-trace recovery may copy the exception across await boundaries.
          assertEquals(failure.javaClass, actual.javaClass)
          assertEquals(failure.message, actual.message)
        }
      }
      assertEquals(1, calls.get())
      assertTrue(scope.isActive)
    } finally { scope.cancel() }
  }
}
