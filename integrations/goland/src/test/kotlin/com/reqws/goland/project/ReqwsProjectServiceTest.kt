package com.reqws.goland.project

import com.intellij.openapi.components.service
import com.intellij.openapi.vcs.ProjectLevelVcsManager
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.reqws.goland.sync.SyncCandidateApplier
import com.reqws.goland.vcs.ReqwsVcsConfigurationMonitor
import com.reqws.goland.vcs.VcsRootInspection
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

class ReqwsProjectServiceTest : BasePlatformTestCase() {
  fun testRegistrationTimeVcsCallbackWaitsForMandatoryPostRegistrationInspection() =
    verifyRegistrationTimeVcsCallbackWaitsForMandatoryPostRegistrationInspection()

  private fun verifyRegistrationTimeVcsCallbackWaitsForMandatoryPostRegistrationInspection() {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val callbackCompleted = AtomicBoolean(false)
    val registrationCount = AtomicInteger(0)
    val registrationClosed = AtomicBoolean(false)
    val registrar = ReqwsVcsChangeRegistrar { listener ->
      registrationCount.incrementAndGet()
      assertNull(listener())
      callbackCompleted.set(true)
      AutoCloseable { registrationClosed.set(true) }
    }

    val service = ReqwsProjectService(project, scope)
    try {
      assertEquals(
        VcsChangeMonitoringStart.STARTED_NOW,
        service.startVcsChangeMonitoring(registrar),
      )
      assertTrue(callbackCompleted.get())
      assertEquals(1, registrationCount.get())
    } finally {
      service.dispose()
      scope.cancel()
    }
    assertTrue(registrationClosed.get())
  }

  fun testOrdinaryProjectNeverRegistersForOrChurnsOnVcsEvents() =
    verifyOrdinaryProjectNeverRegistersForOrChurnsOnVcsEvents()

  private fun verifyOrdinaryProjectNeverRegistersForOrChurnsOnVcsEvents() {
    val configuredRoot = Path.of(requireNotNull(project.basePath)).toAbsolutePath().normalize()
    Files.createDirectories(configuredRoot)
    Files.deleteIfExists(ReqwsProjectDetector.manifestPath(configuredRoot))
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val registrationCount = AtomicInteger(0)
    val service = ReqwsProjectService.createForTest(
      project = project,
      coroutineScope = scope,
      runtimeOverrides = ReqwsProjectServiceRuntimeOverrides(
        vcsChangeRegistrar = ReqwsVcsChangeRegistrar {
          registrationCount.incrementAndGet()
          AutoCloseable {}
        },
      ),
    )
    try {
      awaitSuccessfulCompletion(
        job = requireNotNull(service.refreshAutomatically()),
        description = "ordinary-project startup read",
      )
      awaitStableLifecycle(
        service = service,
        expected = ReqwsLifecycleState.INACTIVE,
        description = "ordinary project settling after startup",
      )
      assertEquals(0, registrationCount.get())

      val stateChanged = CountDownLatch(1)
      val armed = AtomicBoolean(false)
      val stateHandle = service.addListener {
        if (armed.get()) stateChanged.countDown()
      }
      try {
        armed.set(true)
        // Instantiate the bridge only to emit the same platform event. ReqwsProjectService has not
        // attached its external listener because no valid snapshot has ever been accepted.
        project.service<ReqwsVcsConfigurationMonitor>()
        project.messageBus
          .syncPublisher(ProjectLevelVcsManager.VCS_CONFIGURATION_CHANGED)
          .directoryMappingChanged()

        assertFalse(
          "ordinary project published state after a VCS-only event",
          stateChanged.await(NO_CHURN_WINDOW_MILLIS, TimeUnit.MILLISECONDS),
        )
        assertEquals(ReqwsLifecycleState.INACTIVE, service.state.lifecycle)
        assertEquals(0, registrationCount.get())
      } finally {
        stateHandle.close()
      }
    } finally {
      service.dispose()
      scope.cancel()
    }
  }

  fun testFirstValidStateRegistersThenReinspectsAndQueuesConcurrentVcsEvents() =
    verifyFirstValidStateRegistersThenReinspectsAndQueuesConcurrentVcsEvents()

  private fun verifyFirstValidStateRegistersThenReinspectsAndQueuesConcurrentVcsEvents() {
    val root = writeValidManifest()
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val inspectionDigests = CopyOnWriteArrayList<String>()
    val registrationCount = AtomicInteger(0)
    val applyCount = AtomicInteger(0)
    val registeredListener = AtomicReference<(() -> Job?)?>()
    val postInspectionEntered = CountDownLatch(1)
    val allowPostInspection = CountDownLatch(1)
    val callbackReturned = CountDownLatch(1)
    val callbackJob = AtomicReference<Job?>()
    val initialInspection = VcsRootInspection.inspectionFailed()
    val postRegistrationInspection = VcsRootInspection(emptyList(), emptyList())
    val service = ReqwsProjectService.createForTest(
      project = project,
      coroutineScope = scope,
      runtimeOverrides = ReqwsProjectServiceRuntimeOverrides(
        trustGate = ReqwsTrustGate { true },
        candidateApplier = SyncCandidateApplier { applyCount.incrementAndGet() },
        vcsChangeRegistrar = ReqwsVcsChangeRegistrar { listener ->
          registrationCount.incrementAndGet()
          assertEquals(1, inspectionDigests.size)
          registeredListener.set(listener)
          assertNull(listener())
          AutoCloseable {}
        },
        vcsInspector = ReqwsVcsInspector { snapshot ->
          inspectionDigests += snapshot.digestSha256
          if (inspectionDigests.size == 2) {
            postInspectionEntered.countDown()
            check(allowPostInspection.await(5, TimeUnit.SECONDS)) {
              "test did not release post-registration VCS inspection"
            }
          }
          if (inspectionDigests.size == 1) initialInspection else postRegistrationInspection
        },
      ),
    )
    val observed = CopyOnWriteArrayList<ReqwsLifecycleState>()
    val stateHandle = service.addListener { observed += it.lifecycle }
    val callbackThread = Thread({
      try {
        callbackJob.set(registeredListener.get()?.invoke())
      } finally {
        callbackReturned.countDown()
      }
    }, "reqws-vcs-event-during-post-registration-inspection")
    try {
      val initialRead = requireNotNull(service.refreshAutomatically())
      assertTrue(
        "post-registration inspection did not start",
        postInspectionEntered.await(5, TimeUnit.SECONDS),
      )
      assertEquals(1, observed.count { it == ReqwsLifecycleState.READING })

      // STARTED events are enqueued onto IO. The platform publisher returns even while the first
      // valid state's boundary-closing inspection is still running.
      callbackThread.start()
      assertTrue(
        "VCS publisher callback blocked on post-registration inspection",
        callbackReturned.await(5, TimeUnit.SECONDS),
      )
      assertNotNull(callbackJob.get())

      allowPostInspection.countDown()
      awaitSuccessfulCompletion(
        job = initialRead,
        description = "first valid manifest read",
      )
      awaitSuccessfulCompletion(
        job = requireNotNull(callbackJob.get()),
        description = "queued VCS configuration refresh",
      )
      awaitCondition("first valid candidate apply") {
        applyCount.get() == 1 &&
          inspectionDigests.size == 3 &&
          service.state.lifecycle == ReqwsLifecycleState.SYNCHRONIZED
      }

      val digest = requireNotNull(service.state.snapshot).digestSha256
      assertEquals(root, service.state.snapshot?.canonicalProjectRoot)
      assertEquals(1, registrationCount.get())
      assertEquals(listOf(digest, digest), inspectionDigests.take(2))
      assertEquals(postRegistrationInspection, service.state.vcsInspection)
      assertEquals(1, applyCount.get())
    } finally {
      allowPostInspection.countDown()
      callbackThread.join(5_000)
      stateHandle.close()
      service.dispose()
      scope.cancel()
    }
  }

  fun testSafeModeTrustTransitionForcesSameDigestProjectModelReplay() =
    verifySafeModeTrustTransitionForcesSameDigestProjectModelReplay()

  fun testInitialSafeModeSnapshotStillStartsDelayedVcsMonitoring() =
    verifyInitialSafeModeSnapshotStillStartsDelayedVcsMonitoring()

  private fun verifyInitialSafeModeSnapshotStillStartsDelayedVcsMonitoring() {
    writeValidManifest()
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val registrationCount = AtomicInteger(0)
    val inspectionCount = AtomicInteger(0)
    val releasePoll = CompletableDeferred<Unit>()
    val service = ReqwsProjectService.createForTest(
      project = project,
      coroutineScope = scope,
      runtimeOverrides = ReqwsProjectServiceRuntimeOverrides(
        trustGate = ReqwsTrustGate { false },
        candidateApplier = SyncCandidateApplier { error("Safe Mode must not apply") },
        trustPollMillis = TrustTransitionMonitor.MIN_POLL_MILLIS,
        trustPollWaiter = TrustPollWaiter { releasePoll.await() },
        vcsChangeRegistrar = ReqwsVcsChangeRegistrar {
          registrationCount.incrementAndGet()
          AutoCloseable {}
        },
        vcsInspector = ReqwsVcsInspector {
          inspectionCount.incrementAndGet()
          VcsRootInspection(emptyList(), emptyList())
        },
      ),
    )
    try {
      awaitSuccessfulCompletion(
        job = requireNotNull(service.refreshAutomatically()),
        description = "initial Safe Mode read",
      )

      assertEquals(ReqwsLifecycleState.SAFE_MODE_BLOCKED, service.state.lifecycle)
      assertEquals(1, registrationCount.get())
      assertEquals(2, inspectionCount.get())
    } finally {
      service.dispose()
      releasePoll.complete(Unit)
      scope.cancel()
    }
  }

  private fun verifySafeModeTrustTransitionForcesSameDigestProjectModelReplay() {
    writeValidManifest()
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val trusted = AtomicBoolean(true)
    val pollEntered = CountDownLatch(1)
    val releasePoll = CompletableDeferred<Unit>()
    val appliedDigests = CopyOnWriteArrayList<String>()
    val projection = AtomicReference("empty")
    val cleanInspection = VcsRootInspection(emptyList(), emptyList())
    val service = ReqwsProjectService.createForTest(
      project = project,
      coroutineScope = scope,
      runtimeOverrides = ReqwsProjectServiceRuntimeOverrides(
        trustGate = ReqwsTrustGate(trusted::get),
        candidateApplier = SyncCandidateApplier { candidate ->
          appliedDigests += candidate.digestSha256
          projection.set("converged")
        },
        trustPollMillis = TrustTransitionMonitor.MIN_POLL_MILLIS,
        trustPollWaiter = TrustPollWaiter {
          pollEntered.countDown()
          releasePoll.await()
        },
        vcsChangeRegistrar = ReqwsVcsChangeRegistrar { AutoCloseable {} },
        vcsInspector = ReqwsVcsInspector { cleanInspection },
      ),
    )
    try {
      awaitSuccessfulCompletion(
        job = requireNotNull(service.refreshAutomatically()),
        description = "initial trusted apply read",
      )
      awaitCondition("initial trusted apply") {
        appliedDigests.size == 1 && service.state.lifecycle == ReqwsLifecycleState.SYNCHRONIZED
      }
      val digest = appliedDigests.single()

      projection.set("drifted")
      trusted.set(false)
      awaitSuccessfulCompletion(
        job = requireNotNull(service.refreshAutomatically()),
        description = "Safe Mode read",
      )
      assertEquals(ReqwsLifecycleState.SAFE_MODE_BLOCKED, service.state.lifecycle)
      assertTrue("trust monitor did not begin polling", pollEntered.await(5, TimeUnit.SECONDS))

      trusted.set(true)
      releasePoll.complete(Unit)
      awaitCondition("trust-transition forced replay") {
        appliedDigests.size == 2 &&
          service.state.lifecycle == ReqwsLifecycleState.SYNCHRONIZED
      }

      assertEquals(listOf(digest, digest), appliedDigests.toList())
      assertEquals("converged", projection.get())

      // A later ordinary refresh of the unchanged manifest remains a NoOp. The transition intent
      // was submitted and consumed exactly once.
      awaitSuccessfulCompletion(
        job = requireNotNull(service.refreshAutomatically()),
        description = "post-transition automatic read",
      )
      awaitCondition("post-transition NoOp") {
        service.state.lifecycle == ReqwsLifecycleState.SYNCHRONIZED
      }
      assertEquals(2, appliedDigests.size)
    } finally {
      service.dispose()
      scope.cancel()
    }
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
    val startResult = AtomicReference<VcsChangeMonitoringStart?>()
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
      assertEquals(VcsChangeMonitoringStart.UNAVAILABLE, startResult.get())
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

  private fun awaitCondition(description: String, condition: () -> Boolean) {
    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
    while (System.nanoTime() < deadline) {
      if (condition()) return
      Thread.sleep(5)
    }
    assertTrue("$description did not complete", condition())
  }

  private fun awaitStableLifecycle(
    service: ReqwsProjectService,
    expected: ReqwsLifecycleState,
    description: String,
  ) {
    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
    var stableSince = 0L
    while (System.nanoTime() < deadline) {
      val now = System.nanoTime()
      if (service.state.lifecycle == expected) {
        if (stableSince == 0L) stableSince = now
        if (now - stableSince >= TimeUnit.MILLISECONDS.toNanos(STABLE_STATE_MILLIS)) return
      } else {
        stableSince = 0L
      }
      Thread.sleep(5)
    }
    throw AssertionError(
      "$description did not remain $expected for $STABLE_STATE_MILLIS ms",
    )
  }

  private fun writeValidManifest(): Path {
    val configuredRoot = Path.of(requireNotNull(project.basePath)).toAbsolutePath().normalize()
    Files.createDirectories(configuredRoot)
    val root = configuredRoot.toRealPath()
    val manifest = ReqwsProjectDetector.manifestPath(root)
    Files.createDirectories(manifest.parent)
    Files.writeString(
      manifest,
      """
        {
          "schemaVersion": 1,
          "id": "service_test_workspace",
          "name": "Service Test Workspace",
          "featureBranch": "feature/service-test",
          "rootPath": "${escapeJson(root.toString())}",
          "workspaceFilePath": "${escapeJson(root.resolve("workspace.code-workspace").toString())}",
          "repositories": [],
          "createdAt": "2026-08-14T00:00:00.000Z",
          "updatedAt": "2026-08-14T00:00:00.000Z"
        }
      """.trimIndent(),
    )
    return root
  }

  private fun escapeJson(value: String): String =
    value.replace("\\", "\\\\").replace("\"", "\\\"")

  private fun awaitThreadState(thread: Thread, expected: Thread.State): Boolean {
    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
    while (System.nanoTime() < deadline) {
      if (thread.state == expected) return true
      Thread.yield()
    }
    return thread.state == expected
  }

  companion object {
    private const val STABLE_STATE_MILLIS = 100L
    private const val NO_CHURN_WINDOW_MILLIS = 250L
  }
}
