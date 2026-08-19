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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking

class ReqwsProjectServiceTest : BasePlatformTestCase() {
  fun testSynchronousVcsRegistrationCallbackDoesNotStartNestedRefresh() =
    runBlocking {
      verifySynchronousVcsRegistrationCallbackDoesNotStartNestedRefresh()
    }

  private suspend fun verifySynchronousVcsRegistrationCallbackDoesNotStartNestedRefresh() {
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

  fun testAutomaticTrustedRefreshBeforePollStillForcesSameDigestProjectModelReplay() =
    verifyAutomaticTrustedRefreshBeforePollStillForcesSameDigestProjectModelReplay()

  fun testInitialSafeModeSnapshotStillStartsDelayedVcsMonitoring() =
    verifyInitialSafeModeSnapshotStillStartsDelayedVcsMonitoring()

  private fun verifyInitialSafeModeSnapshotStillStartsDelayedVcsMonitoring() {
    writeValidManifest()
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val registrationCount = AtomicInteger(0)
    val inspectionCount = AtomicInteger(0)
    val applyCount = AtomicInteger(0)
    val applyStarted = CountDownLatch(1)
    val releasePoll = CompletableDeferred<Unit>()
    val service = ReqwsProjectService.createForTest(
      project = project,
      coroutineScope = scope,
      runtimeOverrides = ReqwsProjectServiceRuntimeOverrides(
        trustGate = ReqwsTrustGate { false },
        candidateApplier = SyncCandidateApplier {
          applyCount.incrementAndGet()
          applyStarted.countDown()
        },
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
      assertFalse(
        "initial Safe Mode snapshot unexpectedly entered apply",
        applyStarted.await(NO_CHURN_WINDOW_MILLIS, TimeUnit.MILLISECONDS),
      )
      assertEquals(0, applyCount.get())
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
      assertEquals(1, appliedDigests.size)
      assertEquals("drifted", projection.get())

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

  private fun verifyAutomaticTrustedRefreshBeforePollStillForcesSameDigestProjectModelReplay() {
    writeValidManifest()
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val trusted = AtomicBoolean(true)
    val pollEntered = CountDownLatch(1)
    val pollFinished = CountDownLatch(1)
    val pollCancelled = AtomicBoolean(false)
    val releasePoll = CompletableDeferred<Unit>()
    val appliedDigests = CopyOnWriteArrayList<String>()
    val projection = AtomicReference("empty")
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
          try {
            releasePoll.await()
          } finally {
            pollCancelled.set(!releasePoll.isCompleted)
            pollFinished.countDown()
          }
        },
        vcsChangeRegistrar = ReqwsVcsChangeRegistrar { AutoCloseable {} },
        vcsInspector = ReqwsVcsInspector { VcsRootInspection(emptyList(), emptyList()) },
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
      assertEquals(1, appliedDigests.size)
      assertEquals("drifted", projection.get())

      // The automatic read deliberately wins the race with the blocked poll. It cancels that poll
      // after observing trusted state, so correctness cannot depend on the poll submitting its own
      // TRUST_TRANSITION request first.
      trusted.set(true)
      awaitSuccessfulCompletion(
        job = requireNotNull(service.refreshAutomatically()),
        description = "automatic trusted read before trust poll",
      )
      awaitCondition("blocked intent forced automatic replay") {
        appliedDigests.size == 2 &&
          service.state.lifecycle == ReqwsLifecycleState.SYNCHRONIZED
      }

      assertEquals(listOf(digest, digest), appliedDigests.toList())
      assertEquals("converged", projection.get())
      assertTrue("automatic refresh did not cancel the blocked poll", pollFinished.await(5, TimeUnit.SECONDS))
      assertTrue("the blocked poll completed without cancellation", pollCancelled.get())
      releasePoll.complete(Unit)

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
      releasePoll.complete(Unit)
      scope.cancel()
    }
  }

  fun testSupersededFirstValidReadClosesUnacceptedVcsRegistration() =
    verifySupersededFirstValidReadClosesUnacceptedVcsRegistration()

  private fun verifySupersededFirstValidReadClosesUnacceptedVcsRegistration() {
    val root = writeValidManifest()
    val manifest = ReqwsProjectDetector.manifestPath(root)
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val inspectionCount = AtomicInteger(0)
    val registrationCount = AtomicInteger(0)
    val closeCount = AtomicInteger(0)
    val applyCount = AtomicInteger(0)
    val applyStarted = CountDownLatch(1)
    val activeListener = AtomicReference<(() -> Job?)?>()
    val registeredListener = AtomicReference<(() -> Job?)?>()
    val postRegistrationInspectionEntered = CountDownLatch(1)
    val allowPostRegistrationInspection = CountDownLatch(1)
    val callbackBeforeRefreshEntered = CountDownLatch(1)
    val allowCallbackRefresh = CountDownLatch(1)
    val service = ReqwsProjectService.createForTest(
      project = project,
      coroutineScope = scope,
      runtimeOverrides = ReqwsProjectServiceRuntimeOverrides(
        trustGate = ReqwsTrustGate { true },
        candidateApplier = SyncCandidateApplier {
          applyCount.incrementAndGet()
          applyStarted.countDown()
        },
        vcsChangeRegistrar = ReqwsVcsChangeRegistrar { listener ->
          registrationCount.incrementAndGet()
          registeredListener.set(listener)
          activeListener.set(listener)
          AutoCloseable {
            closeCount.incrementAndGet()
            activeListener.compareAndSet(listener, null)
          }
        },
        vcsInspector = ReqwsVcsInspector {
          if (inspectionCount.incrementAndGet() == 2) {
            postRegistrationInspectionEntered.countDown()
            check(allowPostRegistrationInspection.await(5, TimeUnit.SECONDS)) {
              "test did not release post-registration VCS inspection"
            }
          }
          VcsRootInspection(emptyList(), emptyList())
        },
        beforeVcsCallbackRefresh = {
          callbackBeforeRefreshEntered.countDown()
          check(allowCallbackRefresh.await(5, TimeUnit.SECONDS)) {
            "test did not release the captured VCS callback"
          }
        },
      ),
    )
    try {
      val firstValidRead = requireNotNull(service.refreshAutomatically())
      assertTrue(
        "post-registration inspection did not start",
        postRegistrationInspectionEntered.await(5, TimeUnit.SECONDS),
      )
      val capturedCallback = requireNotNull(registeredListener.get()).invoke()
      assertTrue(
        "captured VCS callback did not pass its provisional-listener precheck",
        callbackBeforeRefreshEntered.await(5, TimeUnit.SECONDS),
      )

      Files.delete(manifest)
      awaitSuccessfulCompletion(
        job = requireNotNull(service.refreshAutomatically()),
        description = "newer inactive read",
      )
      awaitStableLifecycle(
        service = service,
        expected = ReqwsLifecycleState.INACTIVE,
        description = "newer inactive read winning",
      )
      awaitCondition("unaccepted registration rollback") { closeCount.get() == 1 }
      assertNull(
        "newer inactive state did not revoke the provisional VCS listener",
        activeListener.get(),
      )
      val stateChanged = CountDownLatch(1)
      val armed = AtomicBoolean(false)
      val stateHandle = service.addListener {
        if (armed.get()) stateChanged.countDown()
      }
      try {
        armed.set(true)
        allowCallbackRefresh.countDown()
        awaitSuccessfulCompletion(
          job = requireNotNull(capturedCallback),
          description = "captured callback after provisional registration closure",
        )
        assertEquals(2, inspectionCount.get())
        assertNull(
          "closed registration epoch still accepted a callback",
          requireNotNull(registeredListener.get()).invoke(),
        )
        assertFalse(
          "closed registration callback caused state churn",
          stateChanged.await(NO_CHURN_WINDOW_MILLIS, TimeUnit.MILLISECONDS),
        )
      } finally {
        stateHandle.close()
      }

      allowPostRegistrationInspection.countDown()
      awaitSuccessfulCompletion(
        job = firstValidRead,
        description = "superseded first valid read",
      )

      assertEquals(1, registrationCount.get())
      assertNull("superseded valid read leaked its VCS listener", activeListener.get())
      assertEquals(1, closeCount.get())
      assertEquals(ReqwsLifecycleState.INACTIVE, service.state.lifecycle)
      assertFalse(
        "superseded candidate unexpectedly entered apply",
        applyStarted.await(NO_CHURN_WINDOW_MILLIS, TimeUnit.MILLISECONDS),
      )
      assertEquals(0, applyCount.get())
    } finally {
      allowCallbackRefresh.countDown()
      allowPostRegistrationInspection.countDown()
      service.dispose()
      scope.cancel()
    }
  }

  fun testNewerInactiveReadRevokesRegistrationReservationBeforeRegistrarReturns() =
    verifyNewerInactiveReadRevokesRegistrationReservationBeforeRegistrarReturns()

  private fun verifyNewerInactiveReadRevokesRegistrationReservationBeforeRegistrarReturns() {
    val root = writeValidManifest()
    val manifest = ReqwsProjectDetector.manifestPath(root)
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val registrationCount = AtomicInteger(0)
    val closeCount = AtomicInteger(0)
    val applyCount = AtomicInteger(0)
    val applyStarted = CountDownLatch(1)
    val registrationEntered = CountDownLatch(1)
    val allowRegistration = CountDownLatch(1)
    val activeListener = AtomicReference<(() -> Job?)?>()
    val registeredListener = AtomicReference<(() -> Job?)?>()
    val service = ReqwsProjectService.createForTest(
      project = project,
      coroutineScope = scope,
      runtimeOverrides = ReqwsProjectServiceRuntimeOverrides(
        trustGate = ReqwsTrustGate { true },
        candidateApplier = SyncCandidateApplier {
          applyCount.incrementAndGet()
          applyStarted.countDown()
        },
        vcsChangeRegistrar = ReqwsVcsChangeRegistrar { listener ->
          registrationCount.incrementAndGet()
          registeredListener.set(listener)
          registrationEntered.countDown()
          check(allowRegistration.await(5, TimeUnit.SECONDS)) {
            "test did not release the external VCS registrar"
          }
          activeListener.set(listener)
          AutoCloseable {
            closeCount.incrementAndGet()
            activeListener.compareAndSet(listener, null)
          }
        },
        vcsInspector = ReqwsVcsInspector {
          VcsRootInspection(emptyList(), emptyList())
        },
      ),
    )
    try {
      val firstValidRead = requireNotNull(service.refreshAutomatically())
      assertTrue(
        "external VCS registration did not start",
        registrationEntered.await(5, TimeUnit.SECONDS),
      )

      Files.delete(manifest)
      awaitSuccessfulCompletion(
        job = requireNotNull(service.refreshAutomatically()),
        description = "newer inactive read during VCS registration",
      )
      awaitStableLifecycle(
        service = service,
        expected = ReqwsLifecycleState.INACTIVE,
        description = "newer inactive read revoking registration reservation",
      )
      assertEquals(1, registrationCount.get())
      assertEquals(0, closeCount.get())
      assertNull(activeListener.get())

      allowRegistration.countDown()
      awaitSuccessfulCompletion(
        job = firstValidRead,
        description = "superseded read returning from VCS registration",
      )
      awaitCondition("late VCS registration handle closure") {
        closeCount.get() == 1 && activeListener.get() == null
      }
      assertNull(
        "revoked registration epoch still accepted a callback",
        requireNotNull(registeredListener.get()).invoke(),
      )
      assertEquals(ReqwsLifecycleState.INACTIVE, service.state.lifecycle)
      assertFalse(
        "superseded registrar owner unexpectedly entered apply",
        applyStarted.await(NO_CHURN_WINDOW_MILLIS, TimeUnit.MILLISECONDS),
      )
      assertEquals(0, applyCount.get())
    } finally {
      allowRegistration.countDown()
      service.dispose()
      scope.cancel()
    }
    assertEquals(1, closeCount.get())
  }

  fun testNewerValidReadJoinsRegistrationEpochWhileRegistrarIsRunning() =
    verifyNewerValidReadJoinsRegistrationEpochWhileRegistrarIsRunning()

  private fun verifyNewerValidReadJoinsRegistrationEpochWhileRegistrarIsRunning() {
    writeValidManifest()
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val inspectionCount = AtomicInteger(0)
    val registrationCount = AtomicInteger(0)
    val closeCount = AtomicInteger(0)
    val applyCount = AtomicInteger(0)
    val registrationEntered = CountDownLatch(1)
    val allowRegistration = CountDownLatch(1)
    val newerInitialInspectionFinished = CountDownLatch(1)
    val newerReadCompleted = CountDownLatch(1)
    val activeListener = AtomicReference<(() -> Job?)?>()
    val service = ReqwsProjectService.createForTest(
      project = project,
      coroutineScope = scope,
      runtimeOverrides = ReqwsProjectServiceRuntimeOverrides(
        trustGate = ReqwsTrustGate { true },
        candidateApplier = SyncCandidateApplier { applyCount.incrementAndGet() },
        vcsChangeRegistrar = ReqwsVcsChangeRegistrar { listener ->
          registrationCount.incrementAndGet()
          registrationEntered.countDown()
          check(allowRegistration.await(5, TimeUnit.SECONDS)) {
            "test did not release the shared VCS registrar"
          }
          activeListener.set(listener)
          AutoCloseable {
            closeCount.incrementAndGet()
            activeListener.compareAndSet(listener, null)
          }
        },
        vcsInspector = ReqwsVcsInspector {
          if (inspectionCount.incrementAndGet() == 2) {
            newerInitialInspectionFinished.countDown()
          }
          VcsRootInspection(emptyList(), emptyList())
        },
      ),
    )
    try {
      val firstValidRead = requireNotNull(service.refreshAutomatically())
      assertTrue(
        "external VCS registration did not start",
        registrationEntered.await(5, TimeUnit.SECONDS),
      )

      val newerValidRead = requireNotNull(service.refreshAutomatically())
      newerValidRead.invokeOnCompletion { newerReadCompleted.countDown() }
      assertTrue(
        "newer valid read did not finish its initial inspection",
        newerInitialInspectionFinished.await(5, TimeUnit.SECONDS),
      )
      assertFalse(
        "newer valid read did not wait for the shared registration epoch",
        newerReadCompleted.await(NO_CHURN_WINDOW_MILLIS, TimeUnit.MILLISECONDS),
      )
      assertEquals(1, registrationCount.get())
      assertEquals(0, applyCount.get())

      allowRegistration.countDown()
      awaitSuccessfulCompletion(
        job = newerValidRead,
        description = "newer valid read adopting in-flight registration",
      )
      awaitSuccessfulCompletion(
        job = firstValidRead,
        description = "superseded registration owner read",
      )
      awaitCondition("newer valid read apply after registration handoff") {
        applyCount.get() == 1 &&
          service.state.lifecycle == ReqwsLifecycleState.SYNCHRONIZED
      }
      assertEquals(1, registrationCount.get())
      assertEquals(4, inspectionCount.get())
      assertEquals(0, closeCount.get())
      assertNotNull(activeListener.get())
    } finally {
      allowRegistration.countDown()
      service.dispose()
      scope.cancel()
    }
    assertEquals(1, closeCount.get())
  }

  fun testNewerValidReadRetriesSharedRegistrationEpochAfterRegistrarFailure() =
    verifyNewerValidReadRetriesSharedRegistrationEpochAfterRegistrarFailure()

  private fun verifyNewerValidReadRetriesSharedRegistrationEpochAfterRegistrarFailure() {
    writeValidManifest()
    val uncaughtRegistrarFailure = AtomicReference<Throwable?>()
    val exceptionHandler = CoroutineExceptionHandler { _, failure ->
      uncaughtRegistrarFailure.compareAndSet(null, failure)
    }
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default + exceptionHandler)
    val inspectionCount = AtomicInteger(0)
    val registrationCount = AtomicInteger(0)
    val closeCount = AtomicInteger(0)
    val applyCount = AtomicInteger(0)
    val firstRegistrationEntered = CountDownLatch(1)
    val allowFirstRegistrationFailure = CountDownLatch(1)
    val secondRegistrationEntered = CountDownLatch(1)
    val newerInitialInspectionFinished = CountDownLatch(1)
    val expectedRegistrarFailure = IllegalStateException("synthetic VCS registrar failure")
    val activeListener = AtomicReference<(() -> Job?)?>()
    val service = ReqwsProjectService.createForTest(
      project = project,
      coroutineScope = scope,
      runtimeOverrides = ReqwsProjectServiceRuntimeOverrides(
        trustGate = ReqwsTrustGate { true },
        candidateApplier = SyncCandidateApplier { applyCount.incrementAndGet() },
        vcsChangeRegistrar = ReqwsVcsChangeRegistrar { listener ->
          when (registrationCount.incrementAndGet()) {
            1 -> {
              firstRegistrationEntered.countDown()
              check(allowFirstRegistrationFailure.await(5, TimeUnit.SECONDS)) {
                "test did not release the failing VCS registrar"
              }
              throw expectedRegistrarFailure
            }
            2 -> {
              secondRegistrationEntered.countDown()
              activeListener.set(listener)
              AutoCloseable {
                closeCount.incrementAndGet()
                activeListener.compareAndSet(listener, null)
              }
            }
            else -> error("unexpected VCS registration attempt")
          }
        },
        vcsInspector = ReqwsVcsInspector {
          if (inspectionCount.incrementAndGet() == 2) {
            newerInitialInspectionFinished.countDown()
          }
          VcsRootInspection(emptyList(), emptyList())
        },
      ),
    )
    try {
      val firstValidRead = requireNotNull(service.refreshAutomatically())
      assertTrue(
        "failing VCS registration did not start",
        firstRegistrationEntered.await(5, TimeUnit.SECONDS),
      )
      val newerValidRead = requireNotNull(service.refreshAutomatically())
      assertTrue(
        "newer valid read did not join the registration epoch",
        newerInitialInspectionFinished.await(5, TimeUnit.SECONDS),
      )

      allowFirstRegistrationFailure.countDown()
      val firstFailure = awaitFailedCompletion(
        job = firstValidRead,
        description = "initial VCS registrar failure",
      )
      assertSame(expectedRegistrarFailure, firstFailure)
      awaitCondition("expected registrar failure delivery") {
        uncaughtRegistrarFailure.get() != null
      }
      assertSame(firstFailure, uncaughtRegistrarFailure.get())
      assertTrue(
        "newer valid read did not retry the shared registration epoch",
        secondRegistrationEntered.await(5, TimeUnit.SECONDS),
      )
      awaitSuccessfulCompletion(
        job = newerValidRead,
        description = "newer valid read after VCS registrar retry",
      )
      awaitCondition("newer valid read apply after VCS registrar retry") {
        applyCount.get() == 1 &&
          service.state.lifecycle == ReqwsLifecycleState.SYNCHRONIZED
      }
      assertEquals(2, registrationCount.get())
      assertEquals(3, inspectionCount.get())
      assertEquals(0, closeCount.get())
      assertNotNull(activeListener.get())
    } finally {
      allowFirstRegistrationFailure.countDown()
      service.dispose()
      scope.cancel()
    }
    assertEquals(1, closeCount.get())
  }

  fun testNewerValidReadAdoptsProvisionalVcsRegistration() =
    verifyNewerValidReadAdoptsProvisionalVcsRegistration()

  private fun verifyNewerValidReadAdoptsProvisionalVcsRegistration() {
    writeValidManifest()
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val inspectionCount = AtomicInteger(0)
    val registrationCount = AtomicInteger(0)
    val closeCount = AtomicInteger(0)
    val applyCount = AtomicInteger(0)
    val activeListener = AtomicReference<(() -> Job?)?>()
    val postRegistrationInspectionEntered = CountDownLatch(1)
    val allowPostRegistrationInspection = CountDownLatch(1)
    val service = ReqwsProjectService.createForTest(
      project = project,
      coroutineScope = scope,
      runtimeOverrides = ReqwsProjectServiceRuntimeOverrides(
        trustGate = ReqwsTrustGate { true },
        candidateApplier = SyncCandidateApplier { applyCount.incrementAndGet() },
        vcsChangeRegistrar = ReqwsVcsChangeRegistrar { listener ->
          registrationCount.incrementAndGet()
          activeListener.set(listener)
          AutoCloseable {
            closeCount.incrementAndGet()
            activeListener.compareAndSet(listener, null)
          }
        },
        vcsInspector = ReqwsVcsInspector {
          if (inspectionCount.incrementAndGet() == 2) {
            postRegistrationInspectionEntered.countDown()
            check(allowPostRegistrationInspection.await(5, TimeUnit.SECONDS)) {
              "test did not release post-registration VCS inspection"
            }
          }
          VcsRootInspection(emptyList(), emptyList())
        },
      ),
    )
    try {
      val firstValidRead = requireNotNull(service.refreshAutomatically())
      assertTrue(
        "post-registration inspection did not start",
        postRegistrationInspectionEntered.await(5, TimeUnit.SECONDS),
      )

      val newerValidRead = requireNotNull(service.refreshAutomatically())
      awaitSuccessfulCompletion(
        job = newerValidRead,
        description = "newer valid read",
      )
      awaitCondition("newer valid read apply") {
        applyCount.get() == 1 &&
          service.state.lifecycle == ReqwsLifecycleState.SYNCHRONIZED
      }

      assertEquals(1, registrationCount.get())
      assertEquals(0, closeCount.get())
      assertNotNull(activeListener.get())

      allowPostRegistrationInspection.countDown()
      awaitSuccessfulCompletion(
        job = firstValidRead,
        description = "superseded first valid read",
      )
      assertEquals(0, closeCount.get())

      awaitSuccessfulCompletion(
        job = requireNotNull(activeListener.get()?.invoke()),
        description = "adopted VCS listener refresh",
      )
      awaitCondition("adopted listener NoOp") {
        service.state.lifecycle == ReqwsLifecycleState.SYNCHRONIZED &&
          inspectionCount.get() == 4
      }
      assertEquals(1, applyCount.get())
      assertEquals(0, closeCount.get())
    } finally {
      allowPostRegistrationInspection.countDown()
      service.dispose()
      scope.cancel()
    }
    assertEquals(1, closeCount.get())
  }

  fun testCancelledPostRegistrationInspectionClosesProvisionalVcsRegistration() =
    verifyCancelledPostRegistrationInspectionClosesProvisionalVcsRegistration()

  private fun verifyCancelledPostRegistrationInspectionClosesProvisionalVcsRegistration() {
    writeValidManifest()
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val inspectionCount = AtomicInteger(0)
    val registrationCount = AtomicInteger(0)
    val closeCount = AtomicInteger(0)
    val applyCount = AtomicInteger(0)
    val activeListener = AtomicReference<(() -> Job?)?>()
    val registeredListeners = CopyOnWriteArrayList<() -> Job?>()
    val postRegistrationInspectionEntered = CountDownLatch(1)
    val allowPostRegistrationInspection = CountDownLatch(1)
    val service = ReqwsProjectService.createForTest(
      project = project,
      coroutineScope = scope,
      runtimeOverrides = ReqwsProjectServiceRuntimeOverrides(
        trustGate = ReqwsTrustGate { true },
        candidateApplier = SyncCandidateApplier { applyCount.incrementAndGet() },
        vcsChangeRegistrar = ReqwsVcsChangeRegistrar { listener ->
          registrationCount.incrementAndGet()
          registeredListeners += listener
          activeListener.set(listener)
          AutoCloseable {
            closeCount.incrementAndGet()
            activeListener.compareAndSet(listener, null)
          }
        },
        vcsInspector = ReqwsVcsInspector {
          if (inspectionCount.incrementAndGet() == 2) {
            postRegistrationInspectionEntered.countDown()
            check(allowPostRegistrationInspection.await(5, TimeUnit.SECONDS)) {
              "test did not release cancelled post-registration inspection"
            }
          }
          VcsRootInspection(emptyList(), emptyList())
        },
      ),
    )
    try {
      val cancelledRead = requireNotNull(service.refreshAutomatically())
      assertTrue(
        "post-registration inspection did not start",
        postRegistrationInspectionEntered.await(5, TimeUnit.SECONDS),
      )
      cancelledRead.cancel(CancellationException("cancel blocked read"))
      allowPostRegistrationInspection.countDown()
      val failure = awaitFailedCompletion(
        job = cancelledRead,
        description = "cancelled post-registration read",
      )
      assertTrue(failure is CancellationException)
      awaitCondition("failed registration rollback") { closeCount.get() == 1 }
      assertNull(activeListener.get())

      awaitSuccessfulCompletion(
        job = requireNotNull(service.refreshAutomatically()),
        description = "valid retry after post-registration cancellation",
      )
      awaitCondition("valid retry apply") {
        applyCount.get() == 1 &&
          service.state.lifecycle == ReqwsLifecycleState.SYNCHRONIZED
      }
      assertEquals(2, registrationCount.get())
      assertEquals(1, closeCount.get())
      assertNotNull(activeListener.get())
      assertNull("closed registration epoch still accepted a callback", registeredListeners[0]())
    } finally {
      allowPostRegistrationInspection.countDown()
      service.dispose()
      scope.cancel()
    }
    assertEquals(2, closeCount.get())
  }

  fun testCancelledNewerReadClosesOlderProvisionalVcsRegistration() =
    verifyCancelledNewerReadClosesOlderProvisionalVcsRegistration()

  private fun verifyCancelledNewerReadClosesOlderProvisionalVcsRegistration() {
    writeValidManifest()
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val inspectionCount = AtomicInteger(0)
    val registrationCount = AtomicInteger(0)
    val closeCount = AtomicInteger(0)
    val applyCount = AtomicInteger(0)
    val applyStarted = CountDownLatch(1)
    val activeListener = AtomicReference<(() -> Job?)?>()
    val postRegistrationInspectionEntered = CountDownLatch(1)
    val allowPostRegistrationInspection = CountDownLatch(1)
    val service = ReqwsProjectService.createForTest(
      project = project,
      coroutineScope = scope,
      runtimeOverrides = ReqwsProjectServiceRuntimeOverrides(
        trustGate = ReqwsTrustGate { true },
        candidateApplier = SyncCandidateApplier {
          applyCount.incrementAndGet()
          applyStarted.countDown()
        },
        vcsChangeRegistrar = ReqwsVcsChangeRegistrar { listener ->
          registrationCount.incrementAndGet()
          activeListener.set(listener)
          AutoCloseable {
            closeCount.incrementAndGet()
            activeListener.compareAndSet(listener, null)
          }
        },
        vcsInspector = ReqwsVcsInspector {
          when (inspectionCount.incrementAndGet()) {
            2 -> {
              postRegistrationInspectionEntered.countDown()
              check(allowPostRegistrationInspection.await(5, TimeUnit.SECONDS)) {
                "test did not release post-registration VCS inspection"
              }
            }
            3 -> throw CancellationException("cancel newer initial inspection")
          }
          VcsRootInspection(emptyList(), emptyList())
        },
      ),
    )
    try {
      val firstValidRead = requireNotNull(service.refreshAutomatically())
      assertTrue(
        "post-registration inspection did not start",
        postRegistrationInspectionEntered.await(5, TimeUnit.SECONDS),
      )

      val failed = awaitFailedCompletion(
        job = requireNotNull(service.refreshAutomatically()),
        description = "cancelled newer initial inspection",
      )
      assertTrue(failed is CancellationException)
      awaitCondition("cancelled latest generation registration rollback") {
        closeCount.get() == 1
      }
      assertNull(activeListener.get())

      allowPostRegistrationInspection.countDown()
      awaitSuccessfulCompletion(
        job = firstValidRead,
        description = "stale first valid read",
      )
      assertEquals(1, registrationCount.get())
      assertEquals(1, closeCount.get())
      assertNull(activeListener.get())
      assertFalse(
        "cancelled generation allowed the stale candidate to apply",
        applyStarted.await(NO_CHURN_WINDOW_MILLIS, TimeUnit.MILLISECONDS),
      )
      assertEquals(0, applyCount.get())
    } finally {
      allowPostRegistrationInspection.countDown()
      service.dispose()
      scope.cancel()
    }
    assertEquals(1, closeCount.get())
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
        startResult.set(runBlocking { service.startVcsChangeMonitoring(registrar) })
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
        "dispose waited for the external VCS registrar",
        disposeFinished.await(5, TimeUnit.SECONDS),
      )
      assertEquals(0, closeCount.get())

      allowRegistration.countDown()
      assertTrue(startFinished.await(5, TimeUnit.SECONDS))
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

  private fun awaitFailedCompletion(job: Job, description: String): Throwable {
    val completion = CountDownLatch(1)
    val failure = AtomicReference<Throwable?>()
    job.invokeOnCompletion { cause ->
      failure.set(cause)
      completion.countDown()
    }
    assertTrue("$description did not complete", completion.await(5, TimeUnit.SECONDS))
    return requireNotNull(failure.get()) { "$description unexpectedly succeeded" }
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

  companion object {
    private const val STABLE_STATE_MILLIS = 100L
    private const val NO_CHURN_WINDOW_MILLIS = 250L
  }
}
