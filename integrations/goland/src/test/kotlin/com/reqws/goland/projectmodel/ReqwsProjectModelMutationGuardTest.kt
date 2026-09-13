package com.reqws.goland.projectmodel

import com.intellij.openapi.components.service
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield

class ReqwsProjectModelMutationGuardTest : BasePlatformTestCase() {
  fun testSynchronousScopesStayActiveUntilTheOutermostScopeReturns() {
    val guard = project.service<ReqwsProjectModelMutationGuard>()

    assertSame(guard, project.service<ReqwsProjectModelMutationGuard>())
    assertFalse(guard.isActive)

    val result = guard.withMutation {
      assertTrue(guard.isActive)
      guard.withMutation {
        assertTrue(guard.isActive)
      }
      assertTrue(guard.isActive)
      "completed"
    }

    assertEquals("completed", result)
    assertFalse(guard.isActive)
  }

  fun testSuspendingScopeStaysActiveAcrossSuspension() = runBlocking {
    val guard = project.service<ReqwsProjectModelMutationGuard>()

    val result = guard.withSuspendingMutation {
      assertTrue(guard.isActive)
      yield()
      assertTrue(guard.isActive)
      42
    }

    assertEquals(42, result)
    assertFalse(guard.isActive)
  }

  fun testSynchronousFailureRestoresAndRearmsTheGuard() {
    val guard = project.service<ReqwsProjectModelMutationGuard>()
    val failure = IllegalStateException("mutation failed")

    val thrown = org.junit.Assert.assertThrows(IllegalStateException::class.java) {
      guard.withMutation {
        assertTrue(guard.isActive)
        throw failure
      }
    }

    assertSame(failure, thrown)
    assertFalse(guard.isActive)
    guard.withMutation {
      assertTrue(guard.isActive)
    }
    assertFalse(guard.isActive)
  }

  fun testSuspendingCancellationRestoresAndRearmsTheGuard() = runBlocking {
    val guard = project.service<ReqwsProjectModelMutationGuard>()
    val cancellation = CancellationException("mutation cancelled")

    val thrown = org.junit.Assert.assertThrows(CancellationException::class.java) {
      runBlocking {
        guard.withSuspendingMutation {
          assertTrue(guard.isActive)
          throw cancellation
        }
      }
    }

    assertSame(cancellation, thrown)
    assertFalse(guard.isActive)
    guard.withSuspendingMutation {
      assertTrue(guard.isActive)
    }
    assertFalse(guard.isActive)
  }
}
