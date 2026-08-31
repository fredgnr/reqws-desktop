package com.reqws.goland.project

import com.intellij.openapi.Disposable
import com.intellij.openapi.components.service
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vcs.ProjectLevelVcsManager
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.reqws.goland.manifest.ManifestSnapshot
import com.reqws.goland.projectmodel.ReqwsProjectModelMutationGuard
import com.reqws.goland.sync.SyncCandidateApplier
import com.reqws.goland.sync.SyncTrigger
import com.reqws.goland.ui.ReqwsToolWindowAvailabilityController
import com.reqws.goland.ui.ReqwsToolWindowViewModel
import com.reqws.goland.vcs.ReqwsVcsConfigurationMonitor
import com.reqws.goland.vcs.VcsRepositoryInspection
import com.reqws.goland.vcs.VcsRepositoryStatus
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
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
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

  fun testSupersededRefreshCannotPublishReadingAfterNewerStableState() =
    verifySupersededRefreshCannotPublishReadingAfterNewerStableState()

  private fun verifySupersededRefreshCannotPublishReadingAfterNewerStableState() {
    writeValidManifest()
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val firstPublicationEntered = CountDownLatch(1)
    val allowFirstPublication = CountDownLatch(1)
    val publicationCount = AtomicInteger(0)
    val applyCount = AtomicInteger(0)
    val firstReturnedJob = AtomicReference<Job?>()
    val service = ReqwsProjectService.createForTest(
      project = project,
      coroutineScope = scope,
      runtimeOverrides = ReqwsProjectServiceRuntimeOverrides(
        trustGate = ReqwsTrustGate { true },
        candidateApplier = SyncCandidateApplier { applyCount.incrementAndGet() },
        vcsChangeRegistrar = ReqwsVcsChangeRegistrar { AutoCloseable {} },
        vcsInspector = ReqwsVcsInspector {
          VcsRootInspection(emptyList(), emptyList())
        },
        beforeReadingPublication = {
          if (publicationCount.incrementAndGet() == 1) {
            firstPublicationEntered.countDown()
            check(allowFirstPublication.await(5, TimeUnit.SECONDS)) {
              "test did not release the superseded READING publication"
            }
          }
        },
      ),
    )
    try {
      val firstCaller = scope.launch {
        firstReturnedJob.set(service.refreshAutomatically())
      }
      check(firstPublicationEntered.await(5, TimeUnit.SECONDS)) {
        "first refresh did not reach the READING publication boundary"
      }

      awaitSuccessfulCompletion(
        job = requireNotNull(service.refreshAutomatically()),
        description = "newer refresh while older publication is held",
      )
      awaitCondition("newer refresh stable state") {
        service.state.lifecycle == ReqwsLifecycleState.SYNCHRONIZED && applyCount.get() == 1
      }

      allowFirstPublication.countDown()
      awaitSuccessfulCompletion(firstCaller, "superseded refresh caller")
      val superseded = requireNotNull(firstReturnedJob.get())
      awaitCondition("superseded refresh cancellation") { superseded.isCompleted }

      assertTrue(superseded.isCancelled)
      assertEquals(ReqwsLifecycleState.SYNCHRONIZED, service.state.lifecycle)
      assertEquals(1, applyCount.get())
    } finally {
      allowFirstPublication.countDown()
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
    val root = writeValidManifest()
    val persistedDigest = com.reqws.goland.manifest.ManifestReader().read(root).digestSha256
    project.service<ReqwsSyncPersistence>().markApplied(persistedDigest)
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
      assertEquals(persistedDigest, service.state.lastAppliedDigest)
      assertNull(service.state.validatedProjectionDigest)
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

  fun testFirstValidRegistrarFailurePublishesStableErrorAndAllowsRegistrationRetry() =
    verifyFirstValidRegistrarFailurePublishesStableErrorAndAllowsRegistrationRetry()

  private fun verifyFirstValidRegistrarFailurePublishesStableErrorAndAllowsRegistrationRetry() {
    writeValidManifest()
    val expectedFailure = IllegalStateException("synthetic isolated VCS registrar failure")
    val reportedFailure = AtomicReference<Throwable?>()
    val exceptionHandler = CoroutineExceptionHandler { _, failure ->
      reportedFailure.compareAndSet(null, failure)
    }
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default + exceptionHandler)
    val registrationCount = AtomicInteger(0)
    val closeCount = AtomicInteger(0)
    val applyCount = AtomicInteger(0)
    val service = ReqwsProjectService.createForTest(
      project = project,
      coroutineScope = scope,
      runtimeOverrides = ReqwsProjectServiceRuntimeOverrides(
        trustGate = ReqwsTrustGate { true },
        candidateApplier = SyncCandidateApplier { applyCount.incrementAndGet() },
        vcsChangeRegistrar = ReqwsVcsChangeRegistrar {
          if (registrationCount.incrementAndGet() == 1) throw expectedFailure
          AutoCloseable { closeCount.incrementAndGet() }
        },
        vcsInspector = ReqwsVcsInspector {
          VcsRootInspection(emptyList(), emptyList())
        },
      ),
    )
    try {
      val failure = awaitFailedCompletion(
        job = requireNotNull(service.refreshAutomatically()),
        description = "isolated first VCS registrar failure",
      )

      assertSame(expectedFailure, failure)
      awaitCondition("isolated VCS registrar failure delivery") {
        reportedFailure.get() != null && service.state.lifecycle == ReqwsLifecycleState.ERROR
      }
      assertSame(expectedFailure, reportedFailure.get())
      assertEquals(ReqwsLifecycleState.ERROR, service.state.lifecycle)
      assertEquals(ReqwsStableErrorCode.REFRESH_FAILED, service.state.lastError?.code)
      assertNull(service.state.snapshot)
      assertNull(service.state.lastAppliedDigest)
      assertEquals(1, registrationCount.get())
      assertEquals(0, applyCount.get())

      awaitSuccessfulCompletion(
        job = requireNotNull(service.refreshAutomatically()),
        description = "valid refresh after isolated VCS registrar failure",
      )
      awaitCondition("registration retry after isolated VCS registrar failure") {
        registrationCount.get() == 2 &&
          applyCount.get() == 1 &&
          service.state.lifecycle == ReqwsLifecycleState.SYNCHRONIZED
      }
      assertEquals(0, closeCount.get())
    } finally {
      service.dispose()
      scope.cancel()
    }
    assertEquals(1, closeCount.get())
  }

  fun testLatestUnexpectedRefreshFailurePublishesStableErrorAndPreservesLastGoodState() =
    verifyLatestUnexpectedRefreshFailurePublishesStableErrorAndPreservesLastGoodState()

  private fun verifyLatestUnexpectedRefreshFailurePublishesStableErrorAndPreservesLastGoodState() {
    writeValidManifest()
    val expectedFailure = IllegalStateException("synthetic refresh failure")
    val reportedFailure = AtomicReference<Throwable?>()
    val exceptionHandler = CoroutineExceptionHandler { _, failure ->
      reportedFailure.compareAndSet(null, failure)
    }
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default + exceptionHandler)
    val failInspection = AtomicBoolean(false)
    val applyCount = AtomicInteger(0)
    val service = ReqwsProjectService.createForTest(
      project = project,
      coroutineScope = scope,
      runtimeOverrides = ReqwsProjectServiceRuntimeOverrides(
        trustGate = ReqwsTrustGate { true },
        candidateApplier = SyncCandidateApplier { applyCount.incrementAndGet() },
        vcsChangeRegistrar = ReqwsVcsChangeRegistrar { AutoCloseable {} },
        vcsInspector = ReqwsVcsInspector {
          if (failInspection.get()) throw expectedFailure
          VcsRootInspection(emptyList(), emptyList())
        },
      ),
    )
    try {
      awaitSuccessfulCompletion(
        job = requireNotNull(service.refreshAutomatically()),
        description = "initial refresh before unexpected failure",
      )
      awaitCondition("initial state before unexpected refresh failure") {
        applyCount.get() == 1 &&
          service.state.lifecycle == ReqwsLifecycleState.SYNCHRONIZED
      }
      val previousSnapshot = requireNotNull(service.state.snapshot)
      val previousDigest = requireNotNull(service.state.lastAppliedDigest)
      val previousValidatedDigest = requireNotNull(service.state.validatedProjectionDigest)

      failInspection.set(true)
      val failure = awaitFailedCompletion(
        job = requireNotNull(service.refreshAutomatically()),
        description = "latest unexpected refresh failure",
      )

      assertSame(expectedFailure, failure)
      awaitCondition("unexpected refresh failure delivery") {
        reportedFailure.get() != null && service.state.lifecycle == ReqwsLifecycleState.ERROR
      }
      assertSame(expectedFailure, reportedFailure.get())
      assertEquals(ReqwsLifecycleState.ERROR, service.state.lifecycle)
      assertSame(previousSnapshot, service.state.snapshot)
      assertEquals(previousDigest, service.state.lastAppliedDigest)
      assertEquals(previousValidatedDigest, service.state.validatedProjectionDigest)
      assertEquals(ReqwsStableErrorCode.REFRESH_FAILED, service.state.lastError?.code)
      assertEquals(previousSnapshot.digestSha256, service.state.lastError?.digestSha256)
      assertTrue(ReqwsToolWindowViewModel.from(service.state).preservedSnapshot)
      assertEquals(1, applyCount.get())
    } finally {
      service.dispose()
      scope.cancel()
    }
  }

  fun testProjectionFailureInvalidatesLiveProofAcrossALaterManifestReadError() =
    verifyProjectionFailureInvalidatesLiveProofAcrossALaterManifestReadError()

  private fun verifyProjectionFailureInvalidatesLiveProofAcrossALaterManifestReadError() {
    val root = writeValidManifestWithRepository()
    val manifest = ReqwsProjectDetector.manifestPath(root)
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val failProjection = AtomicBoolean(false)
    val service = ReqwsProjectService.createForTest(
      project = project,
      coroutineScope = scope,
      runtimeOverrides = ReqwsProjectServiceRuntimeOverrides(
        trustGate = ReqwsTrustGate { true },
        candidateApplier = SyncCandidateApplier {
          if (failProjection.get()) {
            throw ReqwsProjectionApplyException(
              stableCode = ReqwsStableErrorCode.PROJECT_CONTENT_NOT_CONVERGED,
              degraded = true,
              field = "PROJECT_FILE_INDEX",
            )
          }
        },
        vcsChangeRegistrar = ReqwsVcsChangeRegistrar { AutoCloseable {} },
        vcsInspector = ReqwsVcsInspector {
          VcsRootInspection(
            repositoryStatuses = listOf(
              VcsRepositoryInspection(0, VcsRepositoryStatus.CONFIGURED),
            ),
            workspaceDiagnostics = emptyList(),
          )
        },
        manifestWatcherFactory = ReqwsManifestWatcherFactory { _, _, _, _ -> Disposable {} },
      ),
    )
    try {
      awaitSuccessfulCompletion(
        requireNotNull(service.refreshAutomatically()),
        "initial projection before proof invalidation",
      )
      awaitCondition("initial validated projection") {
        service.state.validatedProjectionDigest != null &&
          ReqwsToolWindowViewModel.from(service.state).repositories.single().statusKey ==
          "repository.active"
      }

      failProjection.set(true)
      awaitSuccessfulCompletion(
        requireNotNull(service.refresh()),
        "forced projection failure",
      )
      awaitCondition("projection failure invalidated live proof") {
        service.state.lifecycle == ReqwsLifecycleState.DEGRADED &&
          service.state.lastError?.code == ReqwsStableErrorCode.PROJECT_CONTENT_NOT_CONVERGED
      }
      assertNull(service.state.validatedProjectionDigest)
      assertEquals(
        "repository.projectContentUnavailable",
        ReqwsToolWindowViewModel.from(service.state).repositories.single().statusKey,
      )

      Files.writeString(manifest, "{")
      awaitSuccessfulCompletion(
        requireNotNull(service.refreshAutomatically()),
        "manifest read error after projection failure",
      )
      awaitCondition("manifest read error after proof invalidation") {
        service.state.lifecycle == ReqwsLifecycleState.ERROR &&
          service.state.lastError?.code == "MANIFEST_INVALID_JSON"
      }

      val readErrorView = ReqwsToolWindowViewModel.from(service.state)
      assertNull(service.state.validatedProjectionDigest)
      assertEquals(
        "repository.projectContentUnavailable",
        readErrorView.repositories.single().statusKey,
      )
      assertFalse(readErrorView.preservedSnapshot)
    } finally {
      service.dispose()
      scope.cancel()
    }
  }

  fun testQueuedReadFailureCannotResurrectProofInvalidatedByAnOlderApplyFailure() =
    verifyQueuedReadFailureCannotResurrectProofInvalidatedByAnOlderApplyFailure()

  private fun verifyQueuedReadFailureCannotResurrectProofInvalidatedByAnOlderApplyFailure() {
    val root = writeValidManifestWithRepository()
    val manifest = ReqwsProjectDetector.manifestPath(root)
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val failProjection = AtomicBoolean(false)
    val failingApplyEntered = CountDownLatch(1)
    val allowFailingApply = CountDownLatch(1)
    val service = ReqwsProjectService.createForTest(
      project = project,
      coroutineScope = scope,
      runtimeOverrides = ReqwsProjectServiceRuntimeOverrides(
        trustGate = ReqwsTrustGate { true },
        candidateApplier = SyncCandidateApplier {
          if (failProjection.get()) {
            failingApplyEntered.countDown()
            check(allowFailingApply.await(5, TimeUnit.SECONDS)) {
              "test did not release the failing projection"
            }
            throw ReqwsProjectionApplyException(
              stableCode = ReqwsStableErrorCode.PROJECT_CONTENT_NOT_CONVERGED,
              degraded = true,
              field = "PROJECT_FILE_INDEX",
            )
          }
        },
        vcsChangeRegistrar = ReqwsVcsChangeRegistrar { AutoCloseable {} },
        vcsInspector = ReqwsVcsInspector {
          VcsRootInspection(
            repositoryStatuses = listOf(
              VcsRepositoryInspection(0, VcsRepositoryStatus.CONFIGURED),
            ),
            workspaceDiagnostics = emptyList(),
          )
        },
        manifestWatcherFactory = ReqwsManifestWatcherFactory { _, _, _, _ -> Disposable {} },
      ),
    )
    try {
      awaitSuccessfulCompletion(
        requireNotNull(service.refreshAutomatically()),
        "initial projection before queued read failure",
      )
      awaitCondition("initial proof before queued read failure") {
        service.state.validatedProjectionDigest != null
      }

      failProjection.set(true)
      awaitSuccessfulCompletion(
        requireNotNull(service.refresh()),
        "manual read before blocked projection failure",
      )
      assertTrue(
        "failing projection did not enter its barrier",
        failingApplyEntered.await(5, TimeUnit.SECONDS),
      )
      assertEquals(ReqwsLifecycleState.SYNCHRONIZING, service.state.lifecycle)
      assertNull(service.state.validatedProjectionDigest)

      Files.writeString(manifest, "{")
      awaitSuccessfulCompletion(
        requireNotNull(service.refreshAutomatically()),
        "malformed read queued behind projection failure",
      )
      allowFailingApply.countDown()
      awaitCondition("queued malformed read published after projection failure") {
        service.state.lifecycle == ReqwsLifecycleState.ERROR &&
          service.state.lastError?.code == "MANIFEST_INVALID_JSON"
      }

      val finalView = ReqwsToolWindowViewModel.from(service.state)
      assertNull(service.state.validatedProjectionDigest)
      assertEquals(
        "repository.projectContentUnavailable",
        finalView.repositories.single().statusKey,
      )
      assertFalse(finalView.preservedSnapshot)
    } finally {
      allowFailingApply.countDown()
      service.dispose()
      scope.cancel()
    }
  }

  fun testApplyCancellationInvalidatesLiveProofUntilARecoveryProjectionSucceeds() =
    verifyApplyCancellationInvalidatesLiveProofUntilARecoveryProjectionSucceeds()

  private fun verifyApplyCancellationInvalidatesLiveProofUntilARecoveryProjectionSucceeds() {
    writeValidManifestWithRepository()
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val cancelNextApply = AtomicBoolean(false)
    val applyCount = AtomicInteger(0)
    val service = ReqwsProjectService.createForTest(
      project = project,
      coroutineScope = scope,
      runtimeOverrides = ReqwsProjectServiceRuntimeOverrides(
        trustGate = ReqwsTrustGate { true },
        candidateApplier = SyncCandidateApplier {
          applyCount.incrementAndGet()
          if (cancelNextApply.compareAndSet(true, false)) throw ProcessCanceledException()
        },
        vcsChangeRegistrar = ReqwsVcsChangeRegistrar { AutoCloseable {} },
        vcsInspector = ReqwsVcsInspector {
          VcsRootInspection(
            repositoryStatuses = listOf(
              VcsRepositoryInspection(0, VcsRepositoryStatus.CONFIGURED),
            ),
            workspaceDiagnostics = emptyList(),
          )
        },
        manifestWatcherFactory = ReqwsManifestWatcherFactory { _, _, _, _ -> Disposable {} },
      ),
    )
    try {
      awaitSuccessfulCompletion(
        requireNotNull(service.refreshAutomatically()),
        "initial projection before apply cancellation",
      )
      awaitCondition("initial projection proof before apply cancellation") {
        service.state.validatedProjectionDigest != null && applyCount.get() == 1
      }

      cancelNextApply.set(true)
      awaitSuccessfulCompletion(
        requireNotNull(service.refresh()),
        "manual read before apply cancellation",
      )
      awaitCondition("apply cancellation rollback") {
        applyCount.get() == 2 &&
          service.state.lifecycle == ReqwsLifecycleState.SYNCHRONIZED &&
          service.state.validatedProjectionDigest == null
      }
      val cancelledView = ReqwsToolWindowViewModel.from(service.state)
      assertNull(service.state.lastError)
      assertEquals("state.degraded", cancelledView.statusKey)
      assertEquals(
        "repository.projectContentUnavailable",
        cancelledView.repositories.single().statusKey,
      )

      awaitSuccessfulCompletion(
        requireNotNull(service.refresh()),
        "manual projection recovery after cancellation",
      )
      awaitCondition("projection proof recovery after cancellation") {
        applyCount.get() == 3 && service.state.validatedProjectionDigest != null
      }
      val recoveredView = ReqwsToolWindowViewModel.from(service.state)
      assertEquals("state.synchronized", recoveredView.statusKey)
      assertEquals("repository.active", recoveredView.repositories.single().statusKey)
    } finally {
      service.dispose()
      scope.cancel()
    }
  }

  fun testLatestUnexpectedRefreshFailureWinsAfterOlderApplyCompletes() =
    verifyLatestUnexpectedRefreshFailureWinsAfterOlderApplyCompletes()

  private fun verifyLatestUnexpectedRefreshFailureWinsAfterOlderApplyCompletes() {
    writeValidManifest()
    val expectedFailure = IllegalStateException("synthetic refresh failure during older apply")
    val reportedFailure = AtomicReference<Throwable?>()
    val exceptionHandler = CoroutineExceptionHandler { _, failure ->
      reportedFailure.compareAndSet(null, failure)
    }
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default + exceptionHandler)
    val applyStarted = CountDownLatch(1)
    val allowApply = CountDownLatch(1)
    val applyCount = AtomicInteger(0)
    val failInspection = AtomicBoolean(false)
    val postReleaseStates = CopyOnWriteArrayList<ReqwsLifecycleState>()
    val recordPostRelease = AtomicBoolean(false)
    val service = ReqwsProjectService.createForTest(
      project = project,
      coroutineScope = scope,
      runtimeOverrides = ReqwsProjectServiceRuntimeOverrides(
        trustGate = ReqwsTrustGate { true },
        candidateApplier = SyncCandidateApplier {
          applyCount.incrementAndGet()
          applyStarted.countDown()
          check(allowApply.await(5, TimeUnit.SECONDS)) {
            "test did not release the older candidate apply"
          }
        },
        vcsChangeRegistrar = ReqwsVcsChangeRegistrar { AutoCloseable {} },
        vcsInspector = ReqwsVcsInspector {
          if (failInspection.get()) throw expectedFailure
          VcsRootInspection(emptyList(), emptyList())
        },
      ),
    )
    val stateHandle = service.addListener { next ->
      if (recordPostRelease.get()) postReleaseStates += next.lifecycle
    }
    try {
      awaitSuccessfulCompletion(
        job = requireNotNull(service.refreshAutomatically()),
        description = "initial read before held candidate apply",
      )
      check(applyStarted.await(5, TimeUnit.SECONDS)) {
        "older candidate apply did not start"
      }
      awaitCondition("held candidate applying state") {
        service.state.lifecycle == ReqwsLifecycleState.SYNCHRONIZING
      }
      val previousSnapshot = requireNotNull(service.state.snapshot)
      val previousDigest = service.state.lastAppliedDigest

      failInspection.set(true)
      val failure = awaitFailedCompletion(
        job = requireNotNull(service.refreshAutomatically()),
        description = "latest unexpected failure during older apply",
      )
      assertSame(expectedFailure, failure)
      awaitCondition("held-apply refresh failure delivery") {
        reportedFailure.get() != null
      }
      assertSame(expectedFailure, reportedFailure.get())

      recordPostRelease.set(true)
      allowApply.countDown()
      awaitCondition("older candidate completion event") {
        ReqwsLifecycleState.SYNCHRONIZED in postReleaseStates
      }
      awaitCondition("queued latest refresh failure after older apply") {
        service.state.lifecycle == ReqwsLifecycleState.ERROR
      }

      assertSame(previousSnapshot, service.state.snapshot)
      assertEquals(previousDigest, service.state.lastAppliedDigest)
      assertEquals(ReqwsStableErrorCode.REFRESH_FAILED, service.state.lastError?.code)
      assertEquals(previousSnapshot.digestSha256, service.state.lastError?.digestSha256)
      assertEquals(1, applyCount.get())
    } finally {
      allowApply.countDown()
      stateHandle.close()
      service.dispose()
      scope.cancel()
    }
  }

  fun testReadingListenerFailureCannotLeaveServiceReading() =
    verifyReadingListenerFailureCannotLeaveServiceReading()

  private fun verifyReadingListenerFailureCannotLeaveServiceReading() {
    writeValidManifest()
    val expectedFailure = IllegalStateException("synthetic READING listener failure")
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val applyCount = AtomicInteger(0)
    val service = ReqwsProjectService.createForTest(
      project = project,
      coroutineScope = scope,
      runtimeOverrides = ReqwsProjectServiceRuntimeOverrides(
        trustGate = ReqwsTrustGate { true },
        candidateApplier = SyncCandidateApplier { applyCount.incrementAndGet() },
        vcsChangeRegistrar = ReqwsVcsChangeRegistrar { AutoCloseable {} },
        vcsInspector = ReqwsVcsInspector {
          VcsRootInspection(emptyList(), emptyList())
        },
      ),
    )
    val stateHandle = service.addListener { next ->
      if (next.lifecycle == ReqwsLifecycleState.READING) throw expectedFailure
    }
    try {
      var thrown: Throwable? = null
      try {
        service.refreshAutomatically()
      } catch (failure: Throwable) {
        thrown = failure
      }

      assertSame(expectedFailure, thrown)
      awaitCondition("stable error after READING listener failure") {
        service.state.lifecycle == ReqwsLifecycleState.ERROR
      }
      assertEquals(ReqwsStableErrorCode.REFRESH_FAILED, service.state.lastError?.code)
      assertNull(service.state.snapshot)
      assertNull(service.state.lastAppliedDigest)
      assertEquals(0, applyCount.get())
    } finally {
      stateHandle.close()
      service.dispose()
      scope.cancel()
    }
  }

  fun testLatestRefreshPropagatesProcessCancellationAndRestoresManualSync() =
    verifyLatestRefreshCancellationRestoresManualSync(
      expectedCancellation = ProcessCanceledException(),
      cancellationDescription = "process cancellation",
    )

  fun testLatestRefreshPropagatesCoroutineCancellationAndRestoresManualSync() =
    verifyLatestRefreshCancellationRestoresManualSync(
      expectedCancellation = CancellationException("synthetic coroutine cancellation"),
      cancellationDescription = "coroutine cancellation",
    )

  fun testStartupReadProcessCancellationRetriesAutomatically() =
    verifyStartupReadCancellationRetriesAutomatically(
      expectedCancellation = ProcessCanceledException(),
      cancellationDescription = "process cancellation",
    )

  fun testStartupReadCoroutineCancellationRetriesAutomatically() =
    verifyStartupReadCancellationRetriesAutomatically(
      expectedCancellation = CancellationException("cancel initial startup read"),
      cancellationDescription = "coroutine cancellation",
    )

  private fun verifyStartupReadCancellationRetriesAutomatically(
    expectedCancellation: Throwable,
    cancellationDescription: String,
  ) {
    writeValidManifest()
    val scope = CoroutineScope(
      SupervisorJob() +
        Dispatchers.Default +
        CoroutineExceptionHandler { _, _ -> },
    )
    val inspectionCount = AtomicInteger(0)
    val applyCount = AtomicInteger(0)
    val retryWaitCount = AtomicInteger(0)
    val registrationCount = AtomicInteger(0)
    val registrationCloseCount = AtomicInteger(0)
    val availabilityChanges = CopyOnWriteArrayList<Boolean>()
    val service = ReqwsProjectService.createForTest(
      project = project,
      coroutineScope = scope,
      runtimeOverrides = ReqwsProjectServiceRuntimeOverrides(
        trustGate = ReqwsTrustGate { true },
        candidateApplier = SyncCandidateApplier { applyCount.incrementAndGet() },
        vcsChangeRegistrar = ReqwsVcsChangeRegistrar {
          registrationCount.incrementAndGet()
          AutoCloseable { registrationCloseCount.incrementAndGet() }
        },
        vcsInspector = ReqwsVcsInspector {
          if (inspectionCount.incrementAndGet() == 2) throw expectedCancellation
          VcsRootInspection(emptyList(), emptyList())
        },
        initialCancellationRetryWaiter = InitialCancellationRetryWaiter {
          retryWaitCount.incrementAndGet()
        },
      ),
    )
    try {
      executeStartupActivity(service, availabilityChanges)

      awaitCondition("startup read recovery after $cancellationDescription") {
        retryWaitCount.get() == 1 &&
          inspectionCount.get() >= 4 &&
          applyCount.get() == 1 &&
          service.state.lifecycle == ReqwsLifecycleState.SYNCHRONIZED
      }
      assertEquals(2, registrationCount.get())
      assertEquals(1, registrationCloseCount.get())
      assertNull(service.state.lastError)
      assertNotNull(service.state.lastAppliedDigest)
      val recoveredView = ReqwsToolWindowViewModel.from(service.state)
      assertTrue(recoveredView.visible)
      assertTrue(recoveredView.syncEnabled)
      assertEquals(false, availabilityChanges.first())
      assertEquals(true, availabilityChanges.last())
    } finally {
      service.dispose()
      scope.cancel()
    }
  }

  private fun verifyLatestRefreshCancellationRestoresManualSync(
    expectedCancellation: Throwable,
    cancellationDescription: String,
  ) {
    writeValidManifest()
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val cancelInspection = AtomicBoolean(false)
    val applyCount = AtomicInteger(0)
    val service = ReqwsProjectService.createForTest(
      project = project,
      coroutineScope = scope,
      runtimeOverrides = ReqwsProjectServiceRuntimeOverrides(
        trustGate = ReqwsTrustGate { true },
        candidateApplier = SyncCandidateApplier { applyCount.incrementAndGet() },
        vcsChangeRegistrar = ReqwsVcsChangeRegistrar { AutoCloseable {} },
        vcsInspector = ReqwsVcsInspector {
          if (cancelInspection.get()) throw expectedCancellation
          VcsRootInspection(emptyList(), emptyList())
        },
      ),
    )
    try {
      awaitSuccessfulCompletion(
        job = requireNotNull(service.refreshAutomatically()),
        description = "initial refresh before $cancellationDescription",
      )
      awaitCondition("initial state before $cancellationDescription") {
        applyCount.get() == 1 &&
          service.state.lifecycle == ReqwsLifecycleState.SYNCHRONIZED
      }
      val previousSnapshot = requireNotNull(service.state.snapshot)
      val previousDigest = requireNotNull(service.state.lastAppliedDigest)

      cancelInspection.set(true)
      val failure = awaitFailedCompletion(
        job = requireNotNull(service.refreshAutomatically()),
        description = "latest refresh $cancellationDescription",
      )

      assertSame(expectedCancellation, failure)
      assertEquals(ReqwsLifecycleState.SYNCHRONIZED, service.state.lifecycle)
      assertSame(previousSnapshot, service.state.snapshot)
      assertEquals(previousDigest, service.state.lastAppliedDigest)
      assertNull(service.state.lastError)
      val restoredView = ReqwsToolWindowViewModel.from(service.state)
      assertTrue(restoredView.visible)
      assertTrue("Sync Now stayed disabled after $cancellationDescription", restoredView.syncEnabled)

      cancelInspection.set(false)
      awaitSuccessfulCompletion(
        job = requireNotNull(service.refresh()),
        description = "Tool Window manual sync after $cancellationDescription",
      )
      awaitCondition("manual sync recovery after $cancellationDescription") {
        service.state.lifecycle == ReqwsLifecycleState.SYNCHRONIZED &&
          applyCount.get() == 2
      }
      assertNull(service.state.lastError)
    } finally {
      service.dispose()
      scope.cancel()
    }
  }

  fun testStartupApplyProcessCancellationRetriesAutomatically() =
    verifyStartupApplyCancellationRetriesAutomatically(
      expectedCancellation = ProcessCanceledException(),
      cancellationDescription = "process cancellation",
    )

  fun testStartupApplyCoroutineCancellationRetriesAutomatically() =
    verifyStartupApplyCancellationRetriesAutomatically(
      expectedCancellation = CancellationException("cancel initial startup apply"),
      cancellationDescription = "coroutine cancellation",
    )

  private fun verifyStartupApplyCancellationRetriesAutomatically(
    expectedCancellation: Throwable,
    cancellationDescription: String,
  ) {
    writeValidManifest()
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val applyCount = AtomicInteger(0)
    val retryWaitCount = AtomicInteger(0)
    val availabilityChanges = CopyOnWriteArrayList<Boolean>()
    val service = ReqwsProjectService.createForTest(
      project = project,
      coroutineScope = scope,
      runtimeOverrides = ReqwsProjectServiceRuntimeOverrides(
        trustGate = ReqwsTrustGate { true },
        candidateApplier = SyncCandidateApplier {
          if (applyCount.incrementAndGet() == 1) throw expectedCancellation
        },
        vcsChangeRegistrar = ReqwsVcsChangeRegistrar { AutoCloseable {} },
        vcsInspector = ReqwsVcsInspector {
          VcsRootInspection(emptyList(), emptyList())
        },
        initialCancellationRetryWaiter = InitialCancellationRetryWaiter {
          retryWaitCount.incrementAndGet()
        },
      ),
    )
    try {
      executeStartupActivity(service, availabilityChanges)

      awaitCondition("startup apply recovery after $cancellationDescription") {
        retryWaitCount.get() == 1 &&
          applyCount.get() == 2 &&
          service.state.lifecycle == ReqwsLifecycleState.SYNCHRONIZED
      }
      assertNull(service.state.lastError)
      assertNotNull(service.state.lastAppliedDigest)
      val recoveredView = ReqwsToolWindowViewModel.from(service.state)
      assertTrue(recoveredView.visible)
      assertTrue(recoveredView.syncEnabled)
      assertEquals(false, availabilityChanges.first())
      assertEquals(true, availabilityChanges.last())
    } finally {
      service.dispose()
      scope.cancel()
    }
  }

  fun testApplyRollbackListenerFailureCannotSuppressStartupRetry() =
    verifyApplyRollbackListenerFailureCannotSuppressStartupRetry()

  private fun verifyApplyRollbackListenerFailureCannotSuppressStartupRetry() {
    writeValidManifest()
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val expectedCancellation = ProcessCanceledException()
    val expectedListenerFailure = IllegalStateException("synthetic rollback listener failure")
    val failInactiveDelivery = AtomicBoolean(false)
    val applyCount = AtomicInteger(0)
    val retryWaitCount = AtomicInteger(0)
    val service = ReqwsProjectService.createForTest(
      project = project,
      coroutineScope = scope,
      runtimeOverrides = ReqwsProjectServiceRuntimeOverrides(
        trustGate = ReqwsTrustGate { true },
        candidateApplier = SyncCandidateApplier {
          if (applyCount.incrementAndGet() == 1) throw expectedCancellation
        },
        vcsChangeRegistrar = ReqwsVcsChangeRegistrar { AutoCloseable {} },
        vcsInspector = ReqwsVcsInspector {
          VcsRootInspection(emptyList(), emptyList())
        },
        initialCancellationRetryWaiter = InitialCancellationRetryWaiter {
          retryWaitCount.incrementAndGet()
        },
      ),
    )
    val listenerHandle = service.addListener { next ->
      if (
        next.lifecycle == ReqwsLifecycleState.INACTIVE &&
        failInactiveDelivery.compareAndSet(true, false)
      ) {
        throw expectedListenerFailure
      }
    }
    try {
      failInactiveDelivery.set(true)
      executeStartupActivity(service)

      awaitCondition("startup retry after rollback listener failure") {
        retryWaitCount.get() == 1 &&
          applyCount.get() == 2 &&
          service.state.lifecycle == ReqwsLifecycleState.SYNCHRONIZED
      }
      assertNull(service.state.lastError)
      assertNotNull(service.state.lastAppliedDigest)
    } finally {
      listenerHandle.close()
      service.dispose()
      scope.cancel()
    }
  }

  fun testStartupCancellationRetryIsBounded() =
    verifyStartupCancellationRetryIsBounded()

  private fun verifyStartupCancellationRetryIsBounded() {
    writeValidManifest()
    val scope = CoroutineScope(
      SupervisorJob() +
        Dispatchers.Default +
        CoroutineExceptionHandler { _, _ -> },
    )
    val inspectionCount = AtomicInteger(0)
    val retryWaitCount = AtomicInteger(0)
    val service = ReqwsProjectService.createForTest(
      project = project,
      coroutineScope = scope,
      runtimeOverrides = ReqwsProjectServiceRuntimeOverrides(
        trustGate = ReqwsTrustGate { true },
        vcsInspector = ReqwsVcsInspector {
          inspectionCount.incrementAndGet()
          throw ProcessCanceledException()
        },
        initialCancellationRetryWaiter = InitialCancellationRetryWaiter {
          retryWaitCount.incrementAndGet()
        },
      ),
    )
    try {
      executeStartupActivity(service)

      awaitCondition("bounded startup cancellation retry") {
        inspectionCount.get() == 2 && retryWaitCount.get() == 1
      }
      awaitStableLifecycle(
        service = service,
        expected = ReqwsLifecycleState.INACTIVE,
        description = "bounded startup cancellation fallback",
      )
      assertEquals(2, inspectionCount.get())
      assertEquals(1, retryWaitCount.get())
      assertNull(service.state.lastError)
    } finally {
      service.dispose()
      scope.cancel()
    }
  }

  fun testStartupApplyCancellationRetryIsBounded() =
    verifyStartupApplyCancellationRetryIsBounded()

  private fun verifyStartupApplyCancellationRetryIsBounded() {
    writeValidManifest()
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val applyCount = AtomicInteger(0)
    val retryWaitCount = AtomicInteger(0)
    val service = ReqwsProjectService.createForTest(
      project = project,
      coroutineScope = scope,
      runtimeOverrides = ReqwsProjectServiceRuntimeOverrides(
        trustGate = ReqwsTrustGate { true },
        candidateApplier = SyncCandidateApplier {
          applyCount.incrementAndGet()
          throw ProcessCanceledException()
        },
        vcsChangeRegistrar = ReqwsVcsChangeRegistrar { AutoCloseable {} },
        vcsInspector = ReqwsVcsInspector {
          VcsRootInspection(emptyList(), emptyList())
        },
        initialCancellationRetryWaiter = InitialCancellationRetryWaiter {
          retryWaitCount.incrementAndGet()
        },
      ),
    )
    try {
      executeStartupActivity(service)

      awaitCondition("bounded startup apply cancellation retry") {
        applyCount.get() == 2 && retryWaitCount.get() == 1
      }
      awaitStableLifecycle(
        service = service,
        expected = ReqwsLifecycleState.INACTIVE,
        description = "bounded startup apply cancellation fallback",
      )
      assertEquals(2, applyCount.get())
      assertEquals(1, retryWaitCount.get())
      assertNull(service.state.lastError)
    } finally {
      service.dispose()
      scope.cancel()
    }
  }

  fun testDisposeCancelsPendingStartupCancellationRetry() =
    verifyDisposeCancelsPendingStartupCancellationRetry()

  private fun verifyDisposeCancelsPendingStartupCancellationRetry() {
    writeValidManifest()
    val scope = CoroutineScope(
      SupervisorJob() +
        Dispatchers.Default +
        CoroutineExceptionHandler { _, _ -> },
    )
    val retryWaitEntered = CountDownLatch(1)
    val allowRetryWait = CompletableDeferred<Unit>()
    val inspectionCount = AtomicInteger(0)
    val service = ReqwsProjectService.createForTest(
      project = project,
      coroutineScope = scope,
      runtimeOverrides = ReqwsProjectServiceRuntimeOverrides(
        trustGate = ReqwsTrustGate { true },
        vcsInspector = ReqwsVcsInspector {
          inspectionCount.incrementAndGet()
          throw ProcessCanceledException()
        },
        initialCancellationRetryWaiter = InitialCancellationRetryWaiter {
          retryWaitEntered.countDown()
          allowRetryWait.await()
        },
      ),
    )
    try {
      executeStartupActivity(service)
      assertTrue(
        "startup cancellation retry did not reach its delay",
        retryWaitEntered.await(5, TimeUnit.SECONDS),
      )

      service.dispose()
      allowRetryWait.complete(Unit)
      Thread.sleep(NO_CHURN_WINDOW_MILLIS)

      assertEquals(1, inspectionCount.get())
      assertSame(ReqwsProjectState.DISPOSED, service.state)
    } finally {
      allowRetryWait.complete(Unit)
      service.dispose()
      scope.cancel()
    }
  }

  fun testOwnerScopeCancellationCancelsPendingStartupRetry() =
    verifyOwnerScopeCancellationCancelsPendingStartupRetry()

  private fun verifyOwnerScopeCancellationCancelsPendingStartupRetry() {
    writeValidManifest()
    val scope = CoroutineScope(
      SupervisorJob() +
        Dispatchers.Default +
        CoroutineExceptionHandler { _, _ -> },
    )
    val retryWaitEntered = CountDownLatch(1)
    val allowRetryWait = CompletableDeferred<Unit>()
    val inspectionCount = AtomicInteger(0)
    val service = ReqwsProjectService.createForTest(
      project = project,
      coroutineScope = scope,
      runtimeOverrides = ReqwsProjectServiceRuntimeOverrides(
        trustGate = ReqwsTrustGate { true },
        vcsInspector = ReqwsVcsInspector {
          inspectionCount.incrementAndGet()
          throw ProcessCanceledException()
        },
        initialCancellationRetryWaiter = InitialCancellationRetryWaiter {
          retryWaitEntered.countDown()
          allowRetryWait.await()
        },
      ),
    )
    try {
      executeStartupActivity(service)
      assertTrue(
        "startup retry did not enter its delay before owner cancellation",
        retryWaitEntered.await(5, TimeUnit.SECONDS),
      )

      scope.cancel()
      allowRetryWait.complete(Unit)
      Thread.sleep(NO_CHURN_WINDOW_MILLIS)

      assertEquals(1, inspectionCount.get())
      assertEquals(ReqwsLifecycleState.INACTIVE, service.state.lifecycle)
      assertNull(service.state.lastError)
    } finally {
      allowRetryWait.complete(Unit)
      service.dispose()
      scope.cancel()
    }
  }

  fun testNewerRefreshSupersedesPendingStartupCancellationRetry() =
    verifyNewerRefreshSupersedesPendingStartupCancellationRetry()

  private fun verifyNewerRefreshSupersedesPendingStartupCancellationRetry() {
    writeValidManifest()
    val scope = CoroutineScope(
      SupervisorJob() +
        Dispatchers.Default +
        CoroutineExceptionHandler { _, _ -> },
    )
    val retryWaitEntered = CountDownLatch(1)
    val allowRetryWait = CompletableDeferred<Unit>()
    val cancelFirstInspection = AtomicBoolean(true)
    val inspectionCount = AtomicInteger(0)
    val applyCount = AtomicInteger(0)
    val service = ReqwsProjectService.createForTest(
      project = project,
      coroutineScope = scope,
      runtimeOverrides = ReqwsProjectServiceRuntimeOverrides(
        trustGate = ReqwsTrustGate { true },
        candidateApplier = SyncCandidateApplier { applyCount.incrementAndGet() },
        vcsChangeRegistrar = ReqwsVcsChangeRegistrar { AutoCloseable {} },
        vcsInspector = ReqwsVcsInspector {
          inspectionCount.incrementAndGet()
          if (cancelFirstInspection.compareAndSet(true, false)) {
            throw ProcessCanceledException()
          }
          VcsRootInspection(emptyList(), emptyList())
        },
        initialCancellationRetryWaiter = InitialCancellationRetryWaiter {
          retryWaitEntered.countDown()
          allowRetryWait.await()
        },
      ),
    )
    try {
      executeStartupActivity(service)
      assertTrue(
        "startup cancellation retry did not reach its delay",
        retryWaitEntered.await(5, TimeUnit.SECONDS),
      )

      awaitSuccessfulCompletion(
        job = requireNotNull(service.refreshAutomatically()),
        description = "newer automatic refresh superseding startup retry",
      )
      awaitCondition("newer automatic refresh synchronization") {
        applyCount.get() == 1 &&
          service.state.lifecycle == ReqwsLifecycleState.SYNCHRONIZED
      }
      val inspectionCountAfterWinner = inspectionCount.get()

      allowRetryWait.complete(Unit)
      Thread.sleep(NO_CHURN_WINDOW_MILLIS)

      assertEquals(1, applyCount.get())
      assertEquals(inspectionCountAfterWinner, inspectionCount.get())
      assertNull(service.state.lastError)
    } finally {
      allowRetryWait.complete(Unit)
      service.dispose()
      scope.cancel()
    }
  }

  fun testNewerStablePublicationInvalidatesPendingStartupRetryVersion() =
    verifyNewerStablePublicationInvalidatesPendingStartupRetryVersion()

  private fun verifyNewerStablePublicationInvalidatesPendingStartupRetryVersion() {
    writeValidManifest()
    val expectedCancellation = ProcessCanceledException()
    val scope = CoroutineScope(
      SupervisorJob() +
        Dispatchers.Default +
        CoroutineExceptionHandler { _, _ -> },
    )
    val applyStarted = CountDownLatch(1)
    val allowApply = CountDownLatch(1)
    val retryWaitEntered = CountDownLatch(1)
    val allowRetryWait = CompletableDeferred<Unit>()
    val cancelNextInspection = AtomicBoolean(false)
    val inspectionCount = AtomicInteger(0)
    val applyCount = AtomicInteger(0)
    val service = ReqwsProjectService.createForTest(
      project = project,
      coroutineScope = scope,
      runtimeOverrides = ReqwsProjectServiceRuntimeOverrides(
        trustGate = ReqwsTrustGate { true },
        candidateApplier = SyncCandidateApplier {
          applyCount.incrementAndGet()
          applyStarted.countDown()
          check(allowApply.await(5, TimeUnit.SECONDS)) {
            "test did not release the older startup apply"
          }
        },
        vcsChangeRegistrar = ReqwsVcsChangeRegistrar { AutoCloseable {} },
        vcsInspector = ReqwsVcsInspector {
          inspectionCount.incrementAndGet()
          if (cancelNextInspection.compareAndSet(true, false)) throw expectedCancellation
          VcsRootInspection(emptyList(), emptyList())
        },
        initialCancellationRetryWaiter = InitialCancellationRetryWaiter {
          retryWaitEntered.countDown()
          allowRetryWait.await()
        },
      ),
    )
    try {
      executeStartupActivity(service)
      assertTrue(
        "startup apply did not start before the cancelled read",
        applyStarted.await(5, TimeUnit.SECONDS),
      )
      awaitCondition("startup apply entered SYNCHRONIZING") {
        service.state.lifecycle == ReqwsLifecycleState.SYNCHRONIZING
      }

      cancelNextInspection.set(true)
      val cancelledRead = requireNotNull(service.refreshAutomatically())
      val failure = awaitFailedCompletion(
        job = cancelledRead,
        description = "latest read cancellation during older startup apply",
      )
      assertSame(expectedCancellation, failure)
      assertTrue(
        "startup retry did not enter its delay after exact rollback",
        retryWaitEntered.await(5, TimeUnit.SECONDS),
      )
      assertEquals(ReqwsLifecycleState.INACTIVE, service.state.lifecycle)

      allowApply.countDown()
      awaitCondition("older apply published a newer stable state") {
        service.state.lifecycle == ReqwsLifecycleState.SYNCHRONIZED
      }
      val inspectionCountAfterWinner = inspectionCount.get()

      allowRetryWait.complete(Unit)
      Thread.sleep(NO_CHURN_WINDOW_MILLIS)

      assertEquals(1, applyCount.get())
      assertEquals(inspectionCountAfterWinner, inspectionCount.get())
      assertEquals(ReqwsLifecycleState.SYNCHRONIZED, service.state.lifecycle)
      assertNull(service.state.lastError)
    } finally {
      allowApply.countDown()
      allowRetryWait.complete(Unit)
      service.dispose()
      scope.cancel()
    }
  }

  fun testRefreshCancellationDuringOlderApplyRestoresStateBeforeSynchronizing() =
    verifyRefreshCancellationDuringOlderApplyRestoresStateBeforeSynchronizing()

  private fun verifyRefreshCancellationDuringOlderApplyRestoresStateBeforeSynchronizing() {
    writeValidManifest()
    val expectedCancellation = ProcessCanceledException()
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val cancelInspection = AtomicBoolean(false)
    val secondApplyStarted = CountDownLatch(1)
    val allowSecondApply = CountDownLatch(1)
    val applyCount = AtomicInteger(0)
    val service = ReqwsProjectService.createForTest(
      project = project,
      coroutineScope = scope,
      runtimeOverrides = ReqwsProjectServiceRuntimeOverrides(
        trustGate = ReqwsTrustGate { true },
        candidateApplier = SyncCandidateApplier {
          if (applyCount.incrementAndGet() == 2) {
            secondApplyStarted.countDown()
            check(allowSecondApply.await(5, TimeUnit.SECONDS)) {
              "test did not release the older manual apply"
            }
          }
        },
        vcsChangeRegistrar = ReqwsVcsChangeRegistrar { AutoCloseable {} },
        vcsInspector = ReqwsVcsInspector {
          if (cancelInspection.get()) throw expectedCancellation
          VcsRootInspection(emptyList(), emptyList())
        },
      ),
    )
    try {
      awaitSuccessfulCompletion(
        job = requireNotNull(service.refreshAutomatically()),
        description = "initial refresh before held manual apply",
      )
      awaitCondition("initial stable state before held manual apply") {
        applyCount.get() == 1 &&
          service.state.lifecycle == ReqwsLifecycleState.SYNCHRONIZED
      }
      val stableSnapshot = requireNotNull(service.state.snapshot)
      val stableDigest = requireNotNull(service.state.lastAppliedDigest)

      awaitSuccessfulCompletion(
        job = requireNotNull(service.refresh()),
        description = "manual read before held apply",
      )
      assertTrue(
        "older manual apply did not start",
        secondApplyStarted.await(5, TimeUnit.SECONDS),
      )
      awaitCondition("older manual apply entered synchronizing") {
        service.state.lifecycle == ReqwsLifecycleState.SYNCHRONIZING
      }

      cancelInspection.set(true)
      val failure = awaitFailedCompletion(
        job = requireNotNull(service.refreshAutomatically()),
        description = "latest refresh cancellation during older apply",
      )

      assertSame(expectedCancellation, failure)
      assertEquals(ReqwsLifecycleState.SYNCHRONIZED, service.state.lifecycle)
      assertSame(stableSnapshot, service.state.snapshot)
      assertEquals(stableDigest, service.state.lastAppliedDigest)
      assertNull(service.state.lastError)
      assertTrue(ReqwsToolWindowViewModel.from(service.state).syncEnabled)

      allowSecondApply.countDown()
      awaitCondition("older manual apply completed after cancellation rollback") {
        applyCount.get() == 2 &&
          service.state.lifecycle == ReqwsLifecycleState.SYNCHRONIZED
      }
      assertNull(service.state.lastError)
    } finally {
      allowSecondApply.countDown()
      service.dispose()
      scope.cancel()
    }
  }

  fun testSupersededRefreshCancellationCannotRollBackNewerManualSync() =
    verifySupersededRefreshCancellationCannotRollBackNewerManualSync()

  private fun verifySupersededRefreshCancellationCannotRollBackNewerManualSync() {
    writeValidManifest()
    val expectedCancellation = ProcessCanceledException()
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val blockNextInspection = AtomicBoolean(false)
    val cancelledInspectionEntered = CountDownLatch(1)
    val allowCancelledInspection = CountDownLatch(1)
    val applyCount = AtomicInteger(0)
    val service = ReqwsProjectService.createForTest(
      project = project,
      coroutineScope = scope,
      runtimeOverrides = ReqwsProjectServiceRuntimeOverrides(
        trustGate = ReqwsTrustGate { true },
        candidateApplier = SyncCandidateApplier { applyCount.incrementAndGet() },
        vcsChangeRegistrar = ReqwsVcsChangeRegistrar { AutoCloseable {} },
        vcsInspector = ReqwsVcsInspector {
          if (blockNextInspection.compareAndSet(true, false)) {
            cancelledInspectionEntered.countDown()
            check(allowCancelledInspection.await(5, TimeUnit.SECONDS)) {
              "test did not release the superseded cancelled inspection"
            }
            throw expectedCancellation
          }
          VcsRootInspection(emptyList(), emptyList())
        },
      ),
    )
    try {
      awaitSuccessfulCompletion(
        job = requireNotNull(service.refreshAutomatically()),
        description = "initial refresh before superseded cancellation",
      )
      awaitCondition("initial stable state before superseded cancellation") {
        applyCount.get() == 1 &&
          service.state.lifecycle == ReqwsLifecycleState.SYNCHRONIZED
      }
      val stableDigest = requireNotNull(service.state.lastAppliedDigest)

      blockNextInspection.set(true)
      val superseded = requireNotNull(service.refreshAutomatically())
      assertTrue(
        "cancelled inspection did not start",
        cancelledInspectionEntered.await(5, TimeUnit.SECONDS),
      )

      awaitSuccessfulCompletion(
        job = requireNotNull(service.refresh()),
        description = "newer manual sync while older inspection is blocked",
      )
      awaitCondition("newer manual sync stable state") {
        applyCount.get() == 2 &&
          service.state.lifecycle == ReqwsLifecycleState.SYNCHRONIZED
      }
      val newerSnapshot = requireNotNull(service.state.snapshot)

      allowCancelledInspection.countDown()
      val failure = awaitFailedCompletion(
        job = superseded,
        description = "superseded refresh cancellation",
      )

      assertSame(expectedCancellation, failure)
      assertEquals(ReqwsLifecycleState.SYNCHRONIZED, service.state.lifecycle)
      assertSame(newerSnapshot, service.state.snapshot)
      assertEquals(stableDigest, service.state.lastAppliedDigest)
      assertNull(service.state.lastError)
      assertEquals(2, applyCount.get())
    } finally {
      allowCancelledInspection.countDown()
      service.dispose()
      scope.cancel()
    }
  }

  fun testAppliedStateWinningBeforeReadingPublicationBecomesCancellationBaseline() =
    verifyAppliedStateWinningBeforeReadingPublicationBecomesCancellationBaseline()

  private fun verifyAppliedStateWinningBeforeReadingPublicationBecomesCancellationBaseline() {
    writeValidManifest()
    val expectedCancellation = ProcessCanceledException()
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val cancelInspection = AtomicBoolean(false)
    val secondApplyStarted = CountDownLatch(1)
    val allowSecondApply = CountDownLatch(1)
    val thirdReadingBoundary = CountDownLatch(1)
    val allowThirdReadingPublication = CountDownLatch(1)
    val readingBoundaryCount = AtomicInteger(0)
    val applyCount = AtomicInteger(0)
    val returnedRead = AtomicReference<Job?>()
    val service = ReqwsProjectService.createForTest(
      project = project,
      coroutineScope = scope,
      runtimeOverrides = ReqwsProjectServiceRuntimeOverrides(
        trustGate = ReqwsTrustGate { true },
        candidateApplier = SyncCandidateApplier {
          if (applyCount.incrementAndGet() == 2) {
            secondApplyStarted.countDown()
            check(allowSecondApply.await(5, TimeUnit.SECONDS)) {
              "test did not release the apply before the READING publication"
            }
          }
        },
        vcsChangeRegistrar = ReqwsVcsChangeRegistrar { AutoCloseable {} },
        vcsInspector = ReqwsVcsInspector {
          if (cancelInspection.get()) throw expectedCancellation
          VcsRootInspection(emptyList(), emptyList())
        },
        beforeReadingPublication = {
          if (readingBoundaryCount.incrementAndGet() == 3) {
            thirdReadingBoundary.countDown()
            check(allowThirdReadingPublication.await(5, TimeUnit.SECONDS)) {
              "test did not release the third READING publication"
            }
          }
        },
      ),
    )
    try {
      awaitSuccessfulCompletion(
        job = requireNotNull(service.refreshAutomatically()),
        description = "initial refresh before publication-boundary race",
      )
      awaitCondition("initial stable state before publication-boundary race") {
        applyCount.get() == 1 &&
          service.state.lifecycle == ReqwsLifecycleState.SYNCHRONIZED
      }
      val initialSnapshot = requireNotNull(service.state.snapshot)

      awaitSuccessfulCompletion(
        job = requireNotNull(service.refresh()),
        description = "manual read before publication-boundary race",
      )
      assertTrue(
        "second apply did not start",
        secondApplyStarted.await(5, TimeUnit.SECONDS),
      )
      awaitCondition("second apply entered synchronizing") {
        service.state.lifecycle == ReqwsLifecycleState.SYNCHRONIZING
      }

      cancelInspection.set(true)
      val readCaller = scope.launch {
        returnedRead.set(service.refreshAutomatically())
      }
      assertTrue(
        "third read did not reach its publication boundary",
        thirdReadingBoundary.await(5, TimeUnit.SECONDS),
      )

      allowSecondApply.countDown()
      awaitCondition("older apply published its terminal state first") {
        applyCount.get() == 2 &&
          service.state.lifecycle == ReqwsLifecycleState.SYNCHRONIZED &&
          service.state.snapshot !== initialSnapshot
      }
      val appliedSnapshot = requireNotNull(service.state.snapshot)

      allowThirdReadingPublication.countDown()
      awaitSuccessfulCompletion(readCaller, "third refresh caller")
      val failure = awaitFailedCompletion(
        job = requireNotNull(returnedRead.get()),
        description = "third refresh cancellation after applied state",
      )

      assertSame(expectedCancellation, failure)
      assertEquals(ReqwsLifecycleState.SYNCHRONIZED, service.state.lifecycle)
      assertSame(appliedSnapshot, service.state.snapshot)
      assertNull(service.state.lastError)
    } finally {
      allowSecondApply.countDown()
      allowThirdReadingPublication.countDown()
      service.dispose()
      scope.cancel()
    }
  }

  fun testAppliedStateWinningCancellationRollbackCasCannotBeOverwritten() =
    verifyAppliedStateWinningCancellationRollbackCasCannotBeOverwritten()

  private fun verifyAppliedStateWinningCancellationRollbackCasCannotBeOverwritten() {
    writeValidManifest()
    val expectedCancellation = ProcessCanceledException()
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val cancelInspection = AtomicBoolean(false)
    val secondApplyStarted = CountDownLatch(1)
    val allowSecondApply = CountDownLatch(1)
    val cancellationRollbackEntered = CountDownLatch(1)
    val allowCancellationRollback = CountDownLatch(1)
    val applyCount = AtomicInteger(0)
    val service = ReqwsProjectService.createForTest(
      project = project,
      coroutineScope = scope,
      runtimeOverrides = ReqwsProjectServiceRuntimeOverrides(
        trustGate = ReqwsTrustGate { true },
        candidateApplier = SyncCandidateApplier {
          if (applyCount.incrementAndGet() == 2) {
            secondApplyStarted.countDown()
            check(allowSecondApply.await(5, TimeUnit.SECONDS)) {
              "test did not release the apply before cancellation rollback"
            }
          }
        },
        vcsChangeRegistrar = ReqwsVcsChangeRegistrar { AutoCloseable {} },
        vcsInspector = ReqwsVcsInspector {
          if (cancelInspection.get()) throw expectedCancellation
          VcsRootInspection(emptyList(), emptyList())
        },
        beforeCancellationRollback = {
          cancellationRollbackEntered.countDown()
          check(allowCancellationRollback.await(5, TimeUnit.SECONDS)) {
            "test did not release cancellation rollback"
          }
        },
      ),
    )
    try {
      awaitSuccessfulCompletion(
        job = requireNotNull(service.refreshAutomatically()),
        description = "initial refresh before cancellation-rollback race",
      )
      awaitCondition("initial stable state before cancellation-rollback race") {
        applyCount.get() == 1 &&
          service.state.lifecycle == ReqwsLifecycleState.SYNCHRONIZED
      }

      awaitSuccessfulCompletion(
        job = requireNotNull(service.refresh()),
        description = "manual read before cancellation-rollback race",
      )
      assertTrue(
        "second apply did not start",
        secondApplyStarted.await(5, TimeUnit.SECONDS),
      )
      awaitCondition("second apply entered synchronizing before cancellation rollback") {
        service.state.lifecycle == ReqwsLifecycleState.SYNCHRONIZING
      }

      cancelInspection.set(true)
      val cancelledRead = requireNotNull(service.refreshAutomatically())
      assertTrue(
        "cancelled read did not reach its rollback boundary",
        cancellationRollbackEntered.await(5, TimeUnit.SECONDS),
      )
      assertEquals(ReqwsLifecycleState.READING, service.state.lifecycle)

      allowSecondApply.countDown()
      awaitCondition("older apply won before cancellation rollback CAS") {
        applyCount.get() == 2 &&
          service.state.lifecycle == ReqwsLifecycleState.SYNCHRONIZED
      }
      val appliedSnapshot = requireNotNull(service.state.snapshot)

      allowCancellationRollback.countDown()
      val failure = awaitFailedCompletion(
        job = cancelledRead,
        description = "cancelled read after applied state won",
      )

      assertSame(expectedCancellation, failure)
      assertEquals(ReqwsLifecycleState.SYNCHRONIZED, service.state.lifecycle)
      assertSame(appliedSnapshot, service.state.snapshot)
      assertNull(service.state.lastError)
    } finally {
      allowSecondApply.countDown()
      allowCancellationRollback.countDown()
      service.dispose()
      scope.cancel()
    }
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
    val expectedCancellation = CancellationException("cancel newer initial inspection")
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val inspectionCount = AtomicInteger(0)
    val registrationCount = AtomicInteger(0)
    val closeCount = AtomicInteger(0)
    val applyCount = AtomicInteger(0)
    val applyStarted = CountDownLatch(1)
    val activeListener = AtomicReference<(() -> Job?)?>()
    val postRegistrationInspectionEntered = CountDownLatch(1)
    val allowPostRegistrationInspection = CountDownLatch(1)
    val retryWaitEntered = CountDownLatch(1)
    val allowRetryWait = CompletableDeferred<Unit>()
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
            3 -> throw expectedCancellation
          }
          VcsRootInspection(emptyList(), emptyList())
        },
        initialCancellationRetryWaiter = InitialCancellationRetryWaiter {
          retryWaitEntered.countDown()
          allowRetryWait.await()
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
      assertSame(expectedCancellation, failed)
      assertTrue(
        "startup cancellation retry did not reach its delay",
        retryWaitEntered.await(5, TimeUnit.SECONDS),
      )
      awaitCondition("cancelled latest generation registration rollback") {
        closeCount.get() == 1 && activeListener.get() == null
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
      allowRetryWait.complete(Unit)
      scope.cancel()
    }
    assertEquals(1, closeCount.get())
  }

  fun testDisposeClosesRegistrationThatReturnsAfterTerminalState() =
    verifyDisposeClosesRegistrationThatReturnsAfterTerminalState()

  fun testExternalProjectModelChangeForcesSameDigestReplay() =
    verifyExternalProjectModelChangeForcesSameDigestReplay()

  private fun verifyExternalProjectModelChangeForcesSameDigestReplay() {
    writeValidManifest()
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val appliedDigests = CopyOnWriteArrayList<String>()
    val listener = AtomicReference<((ReqwsProjectModelChangeKind) -> Unit)?>()
    val closeCount = AtomicInteger(0)
    val debounceEntered = CountDownLatch(1)
    val releaseDebounce = CompletableDeferred<Unit>()
    val service = ReqwsProjectService.createForTest(
      project = project,
      coroutineScope = scope,
      runtimeOverrides = projectModelChangeRuntimeOverrides(
        candidateApplier = SyncCandidateApplier { candidate ->
          appliedDigests += candidate.digestSha256
        },
        registrar = ReqwsProjectModelChangeRegistrar { callback ->
          listener.set(callback)
          AutoCloseable { closeCount.incrementAndGet() }
        },
        debounceWaiter = ReqwsProjectModelChangeDebounceWaiter { delayMillis ->
          assertEquals(250L, delayMillis)
          debounceEntered.countDown()
          releaseDebounce.await()
        },
      ),
    )
    try {
      awaitSuccessfulCompletion(
        job = requireNotNull(service.refreshAutomatically()),
        description = "initial project-model projection",
      )
      awaitCondition("initial project-model apply") { appliedDigests.size == 1 }
      val digest = appliedDigests.single()

      requireNotNull(listener.get()).invoke(ReqwsProjectModelChangeKind.WORKSPACE_MODEL_ONLY)
      assertTrue(
        "project-model change did not enter debounce",
        debounceEntered.await(5, TimeUnit.SECONDS),
      )
      releaseDebounce.complete(Unit)
      awaitCondition("same-digest project-model replay") {
        appliedDigests.size == 2 &&
          service.state.lifecycle == ReqwsLifecycleState.SYNCHRONIZED
      }

      assertEquals(listOf(digest, digest), appliedDigests.toList())
      awaitSuccessfulCompletion(
        job = requireNotNull(service.refreshAutomatically()),
        description = "automatic same-digest no-op after project-model replay",
      )
      assertEquals(2, appliedDigests.size)
    } finally {
      releaseDebounce.complete(Unit)
      service.dispose()
      scope.cancel()
    }
    assertEquals(1, closeCount.get())
  }

  fun testGuardedOwnedProjectModelChangeIsIgnored() =
    verifyGuardedOwnedProjectModelChangeIsIgnored()

  private fun verifyGuardedOwnedProjectModelChangeIsIgnored() {
    writeValidManifest()
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val applyCount = AtomicInteger(0)
    val debounceCount = AtomicInteger(0)
    val debounceStarted = CountDownLatch(1)
    val listener = AtomicReference<((ReqwsProjectModelChangeKind) -> Unit)?>()
    val service = ReqwsProjectService.createForTest(
      project = project,
      coroutineScope = scope,
      runtimeOverrides = projectModelChangeRuntimeOverrides(
        candidateApplier = SyncCandidateApplier { applyCount.incrementAndGet() },
        registrar = ReqwsProjectModelChangeRegistrar { callback ->
          listener.set(callback)
          AutoCloseable {}
        },
        debounceWaiter = ReqwsProjectModelChangeDebounceWaiter {
          debounceCount.incrementAndGet()
          debounceStarted.countDown()
        },
      ),
    )
    try {
      awaitSuccessfulCompletion(
        job = requireNotNull(service.refreshAutomatically()),
        description = "initial guarded-event projection",
      )
      awaitCondition("initial guarded-event apply") { applyCount.get() == 1 }

      project.service<ReqwsProjectModelMutationGuard>().withMutation {
        requireNotNull(listener.get()).invoke(ReqwsProjectModelChangeKind.WORKSPACE_MODEL_ONLY)
      }

      assertFalse(
        "guarded project-model event started a debounce",
        debounceStarted.await(NO_CHURN_WINDOW_MILLIS, TimeUnit.MILLISECONDS),
      )
      assertEquals(0, debounceCount.get())
      assertEquals(1, applyCount.get())
    } finally {
      service.dispose()
      scope.cancel()
    }
  }

  fun testProjectModelChangeWithoutValidSnapshotIsIgnored() =
    verifyProjectModelChangeWithoutValidSnapshotIsIgnored()

  private fun verifyProjectModelChangeWithoutValidSnapshotIsIgnored() {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val listener = AtomicReference<((ReqwsProjectModelChangeKind) -> Unit)?>()
    val debounceStarted = CountDownLatch(1)
    val closeCount = AtomicInteger(0)
    val service = ReqwsProjectService.createForTest(
      project = project,
      coroutineScope = scope,
      runtimeOverrides = projectModelChangeRuntimeOverrides(
        candidateApplier = SyncCandidateApplier {
          fail("a project-model event without a valid snapshot reached apply")
        },
        registrar = ReqwsProjectModelChangeRegistrar { callback ->
          listener.set(callback)
          AutoCloseable { closeCount.incrementAndGet() }
        },
        debounceWaiter = ReqwsProjectModelChangeDebounceWaiter {
          debounceStarted.countDown()
        },
      ),
    )
    try {
      assertNull(service.state.snapshot)
      requireNotNull(listener.get()).invoke(ReqwsProjectModelChangeKind.WORKSPACE_MODEL_ONLY)
      assertFalse(
        "project-model event without a snapshot started a debounce",
        debounceStarted.await(NO_CHURN_WINDOW_MILLIS, TimeUnit.MILLISECONDS),
      )
      assertEquals(ReqwsLifecycleState.INACTIVE, service.state.lifecycle)
    } finally {
      service.dispose()
      scope.cancel()
    }
    assertEquals(1, closeCount.get())
  }

  fun testProjectModelChangeBurstDebouncesToOneForcedReplay() =
    verifyProjectModelChangeBurstDebouncesToOneForcedReplay()

  private fun verifyProjectModelChangeBurstDebouncesToOneForcedReplay() {
    writeValidManifest()
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val applyCount = AtomicInteger(0)
    val listener = AtomicReference<((ReqwsProjectModelChangeKind) -> Unit)?>()
    val debounceEntered = CountDownLatch(1)
    val releaseDebounce = CompletableDeferred<Unit>()
    val service = ReqwsProjectService.createForTest(
      project = project,
      coroutineScope = scope,
      runtimeOverrides = projectModelChangeRuntimeOverrides(
        candidateApplier = SyncCandidateApplier { applyCount.incrementAndGet() },
        registrar = ReqwsProjectModelChangeRegistrar { callback ->
          listener.set(callback)
          AutoCloseable {}
        },
        debounceWaiter = ReqwsProjectModelChangeDebounceWaiter {
          debounceEntered.countDown()
          releaseDebounce.await()
        },
      ),
    )
    try {
      awaitSuccessfulCompletion(
        job = requireNotNull(service.refreshAutomatically()),
        description = "initial burst projection",
      )
      awaitCondition("initial burst apply") { applyCount.get() == 1 }

      repeat(8) {
        requireNotNull(listener.get()).invoke(ReqwsProjectModelChangeKind.WORKSPACE_MODEL_ONLY)
      }
      assertTrue(
        "project-model burst did not enter debounce",
        debounceEntered.await(5, TimeUnit.SECONDS),
      )
      releaseDebounce.complete(Unit)
      awaitCondition("debounced project-model replay") {
        applyCount.get() == 2 && service.state.lifecycle == ReqwsLifecycleState.SYNCHRONIZED
      }
      Thread.sleep(NO_CHURN_WINDOW_MILLIS)
      assertEquals(2, applyCount.get())
    } finally {
      releaseDebounce.complete(Unit)
      service.dispose()
      scope.cancel()
    }
  }

  fun testDisposeCancelsProjectModelDebounceAndClosesRegistration() =
    verifyDisposeCancelsProjectModelDebounceAndClosesRegistration()

  private fun verifyDisposeCancelsProjectModelDebounceAndClosesRegistration() {
    writeValidManifest()
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val applyCount = AtomicInteger(0)
    val listener = AtomicReference<((ReqwsProjectModelChangeKind) -> Unit)?>()
    val closeCount = AtomicInteger(0)
    val debounceEntered = CountDownLatch(1)
    val debounceCancelled = CountDownLatch(1)
    val neverRelease = CompletableDeferred<Unit>()
    val service = ReqwsProjectService.createForTest(
      project = project,
      coroutineScope = scope,
      runtimeOverrides = projectModelChangeRuntimeOverrides(
        candidateApplier = SyncCandidateApplier { applyCount.incrementAndGet() },
        registrar = ReqwsProjectModelChangeRegistrar { callback ->
          listener.set(callback)
          AutoCloseable { closeCount.incrementAndGet() }
        },
        debounceWaiter = ReqwsProjectModelChangeDebounceWaiter {
          debounceEntered.countDown()
          try {
            neverRelease.await()
          } finally {
            debounceCancelled.countDown()
          }
        },
      ),
    )
    try {
      awaitSuccessfulCompletion(
        job = requireNotNull(service.refreshAutomatically()),
        description = "initial dispose-debounce projection",
      )
      awaitCondition("initial dispose-debounce apply") { applyCount.get() == 1 }
      requireNotNull(listener.get()).invoke(ReqwsProjectModelChangeKind.WORKSPACE_MODEL_ONLY)
      assertTrue(
        "dispose test debounce did not start",
        debounceEntered.await(5, TimeUnit.SECONDS),
      )

      service.dispose()

      assertTrue(
        "dispose did not cancel project-model debounce",
        debounceCancelled.await(5, TimeUnit.SECONDS),
      )
      assertEquals(1, closeCount.get())
      assertEquals(1, applyCount.get())
      requireNotNull(listener.get()).invoke(ReqwsProjectModelChangeKind.WORKSPACE_MODEL_ONLY)
      assertEquals(1, applyCount.get())
      service.dispose()
      assertEquals(1, closeCount.get())
    } finally {
      neverRelease.complete(Unit)
      service.dispose()
      scope.cancel()
    }
  }

  fun testLateOrdinaryProjectModelCallbackRacingDisposeIsDroppedWithoutFailure() =
    verifyLateOrdinaryProjectModelCallbackRacingDisposeIsDroppedWithoutFailure()

  private fun verifyLateOrdinaryProjectModelCallbackRacingDisposeIsDroppedWithoutFailure() {
    writeValidManifest()
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val listener = AtomicReference<((ReqwsProjectModelChangeKind) -> Unit)?>()
    val applyCount = AtomicInteger(0)
    val debounceCount = AtomicInteger(0)
    val intentGateEntered = CountDownLatch(1)
    val allowIntentCapture = CountDownLatch(1)
    val callbackFailure = AtomicReference<Throwable?>()
    val disposeFailure = AtomicReference<Throwable?>()
    val baseOverrides = projectModelChangeRuntimeOverrides(
      candidateApplier = SyncCandidateApplier { applyCount.incrementAndGet() },
      registrar = ReqwsProjectModelChangeRegistrar { callback ->
        listener.set(callback)
        AutoCloseable {}
      },
      debounceWaiter = ReqwsProjectModelChangeDebounceWaiter {
        debounceCount.incrementAndGet()
      },
    )
    val service = ReqwsProjectService.createForTest(
      project = project,
      coroutineScope = scope,
      runtimeOverrides = baseOverrides.copy(
        beforeProjectModelIntentCapture = {
          intentGateEntered.countDown()
          check(allowIntentCapture.await(5, TimeUnit.SECONDS)) {
            "test did not release project-model intent capture"
          }
        },
      ),
    )
    val callbackThread = Thread({
      try {
        requireNotNull(listener.get()).invoke(ReqwsProjectModelChangeKind.ORDINARY)
      } catch (failure: Throwable) {
        callbackFailure.set(failure)
      }
    }, "reqws-late-project-model-callback")
    val disposeThread = Thread({
      try {
        service.dispose()
      } catch (failure: Throwable) {
        disposeFailure.set(failure)
      }
    }, "reqws-dispose-during-project-model-callback")
    try {
      awaitSuccessfulCompletion(
        requireNotNull(service.refreshAutomatically()),
        "initial projection before late project-model callback",
      )
      awaitCondition("initial apply before late project-model callback") {
        applyCount.get() == 1
      }

      callbackThread.start()
      assertTrue(
        "project-model callback did not pass the initial schedule gate",
        intentGateEntered.await(5, TimeUnit.SECONDS),
      )
      disposeThread.start()
      awaitCondition("terminal state before project-model intent capture") {
        service.state.lifecycle == ReqwsLifecycleState.DISPOSED
      }
      allowIntentCapture.countDown()
      callbackThread.join(5_000)
      disposeThread.join(5_000)

      assertFalse(callbackThread.isAlive)
      assertFalse(disposeThread.isAlive)
      assertNull(callbackFailure.get())
      assertNull(disposeFailure.get())
      assertEquals(0, debounceCount.get())
      assertEquals(1, applyCount.get())
      assertSame(ReqwsProjectState.DISPOSED, service.state)
    } finally {
      allowIntentCapture.countDown()
      callbackThread.join(5_000)
      disposeThread.join(5_000)
      service.dispose()
      scope.cancel()
    }
  }

  fun testThrowingProjectModelRegistrationCloseDoesNotSkipLaterCleanup() =
    verifyThrowingProjectModelRegistrationCloseDoesNotSkipLaterCleanup()

  private fun verifyThrowingProjectModelRegistrationCloseDoesNotSkipLaterCleanup() {
    writeValidManifest()
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val registrationFailure = IllegalStateException("synthetic project-model registration failure")
    val watcherFailure = IllegalArgumentException("synthetic watcher cleanup failure")
    val registrationCloseCount = AtomicInteger(0)
    val watcherCloseCount = AtomicInteger(0)
    val service = ReqwsProjectService.createForTest(
      project = project,
      coroutineScope = scope,
      runtimeOverrides = ReqwsProjectServiceRuntimeOverrides(
        trustGate = ReqwsTrustGate { true },
        candidateApplier = SyncCandidateApplier {},
        vcsChangeRegistrar = ReqwsVcsChangeRegistrar { AutoCloseable {} },
        projectModelChangeRegistrar = ReqwsProjectModelChangeRegistrar {
          AutoCloseable {
            registrationCloseCount.incrementAndGet()
            throw registrationFailure
          }
        },
        vcsInspector = ReqwsVcsInspector { VcsRootInspection(emptyList(), emptyList()) },
        manifestWatcherFactory = ReqwsManifestWatcherFactory { _, _, _, _ ->
          Disposable {
            watcherCloseCount.incrementAndGet()
            throw watcherFailure
          }
        },
      ),
    )
    try {
      awaitSuccessfulCompletion(
        job = requireNotNull(service.refreshAutomatically()),
        description = "projection installing throwing cleanup resources",
      )

      val failure = captureFailure(service::dispose)

      assertSame(registrationFailure, failure)
      assertEquals(listOf(watcherFailure), failure.suppressed.toList())
      assertSame(ReqwsProjectState.DISPOSED, service.state)
      assertNull(service.refreshAutomatically())
      assertEquals(1, registrationCloseCount.get())
      assertEquals(1, watcherCloseCount.get())
    } finally {
      service.dispose()
      scope.cancel()
    }
  }

  fun testVgoOnlyFollowUpEventForcesAtMostOneAdditionalReplayWithoutLooping() =
    verifyVgoOnlyFollowUpEventForcesAtMostOneAdditionalReplayWithoutLooping()

  private fun verifyVgoOnlyFollowUpEventForcesAtMostOneAdditionalReplayWithoutLooping() {
    writeValidManifest()
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val applyCount = AtomicInteger(0)
    val applyTriggers = CopyOnWriteArrayList<SyncTrigger>()
    val debounceCount = AtomicInteger(0)
    val listener = AtomicReference<((ReqwsProjectModelChangeKind) -> Unit)?>()
    val debounceReleases = Channel<Unit>(Channel.UNLIMITED)
    val service = ReqwsProjectService.createForTest(
      project = project,
      coroutineScope = scope,
      runtimeOverrides = projectModelChangeRuntimeOverrides(
        candidateApplier = SyncCandidateApplier { candidate ->
          applyCount.incrementAndGet()
          applyTriggers.add(candidate.trigger)
        },
        registrar = ReqwsProjectModelChangeRegistrar { callback ->
          listener.set(callback)
          AutoCloseable {}
        },
        debounceWaiter = ReqwsProjectModelChangeDebounceWaiter {
          debounceCount.incrementAndGet()
          debounceReleases.receive()
        },
      ),
    )
    try {
      awaitSuccessfulCompletion(
        job = requireNotNull(service.refreshAutomatically()),
        description = "initial Vgo-follow-up projection",
      )
      awaitCondition("initial Vgo-follow-up apply") { applyCount.get() == 1 }

      requireNotNull(listener.get()).invoke(ReqwsProjectModelChangeKind.WORKSPACE_MODEL_ONLY)
      awaitCondition("external project-model debounce") { debounceCount.get() == 1 }
      assertTrue(debounceReleases.trySend(Unit).isSuccess)
      awaitCondition("primary project-model replay") {
        applyCount.get() == 2 && service.state.lifecycle == ReqwsLifecycleState.SYNCHRONIZED
      }

      // Deliver the language plugin's ordinary event only after the primary replay has fully
      // completed. Event classification, not timing overlap, must make this verify-only.
      requireNotNull(listener.get()).invoke(ReqwsProjectModelChangeKind.ORDINARY)
      awaitCondition("Vgo-only follow-up debounce") { debounceCount.get() == 2 }
      assertTrue(debounceReleases.trySend(Unit).isSuccess)
      awaitCondition("bounded Vgo-only follow-up replay") {
        applyCount.get() == 3 && service.state.lifecycle == ReqwsLifecycleState.SYNCHRONIZED
      }

      Thread.sleep(NO_CHURN_WINDOW_MILLIS)
      assertEquals(2, debounceCount.get())
      assertEquals(3, applyCount.get())
      assertEquals(
        listOf(
          SyncTrigger.AUTOMATIC,
          SyncTrigger.PROJECT_MODEL_CHANGE,
          SyncTrigger.PROJECT_MODEL_FOLLOW_UP,
        ),
        applyTriggers,
      )
    } finally {
      debounceReleases.close()
      service.dispose()
      scope.cancel()
    }
  }

  fun testOverlappingOrdinaryEventsKeepTheirOwnVerifyOnlyLineage() =
    verifyOverlappingOrdinaryEventsKeepTheirOwnVerifyOnlyLineage()

  private fun verifyOverlappingOrdinaryEventsKeepTheirOwnVerifyOnlyLineage() {
    writeValidManifest()
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val applyTriggers = CopyOnWriteArrayList<SyncTrigger>()
    val listener = AtomicReference<((ReqwsProjectModelChangeKind) -> Unit)?>()
    val debounceCount = AtomicInteger(0)
    val debounceReleases = Channel<Unit>(Channel.UNLIMITED)
    val candidateOfferCount = AtomicInteger(0)
    val firstFollowUpOfferEntered = CountDownLatch(1)
    val allowFirstFollowUpOffer = CountDownLatch(1)
    val baseOverrides = projectModelChangeRuntimeOverrides(
      candidateApplier = SyncCandidateApplier { candidate ->
        applyTriggers.add(candidate.trigger)
      },
      registrar = ReqwsProjectModelChangeRegistrar { callback ->
        listener.set(callback)
        AutoCloseable {}
      },
      debounceWaiter = ReqwsProjectModelChangeDebounceWaiter {
        debounceCount.incrementAndGet()
        debounceReleases.receive()
      },
    )
    val service = ReqwsProjectService.createForTest(
      project = project,
      coroutineScope = scope,
      runtimeOverrides = baseOverrides.copy(
        beforeCandidateOffer = {
          if (candidateOfferCount.incrementAndGet() == 2) {
            firstFollowUpOfferEntered.countDown()
            check(allowFirstFollowUpOffer.await(5, TimeUnit.SECONDS)) {
              "test did not release the first follow-up candidate"
            }
          }
        },
      ),
    )
    try {
      awaitSuccessfulCompletion(
        requireNotNull(service.refreshAutomatically()),
        "initial projection before overlapping project-model events",
      )
      awaitCondition("initial projection apply") { applyTriggers.size == 1 }

      requireNotNull(listener.get()).invoke(ReqwsProjectModelChangeKind.ORDINARY)
      awaitCondition("first ordinary-event debounce") { debounceCount.get() == 1 }
      assertTrue(debounceReleases.trySend(Unit).isSuccess)
      assertTrue(
        "first follow-up read did not reach candidate selection",
        firstFollowUpOfferEntered.await(5, TimeUnit.SECONDS),
      )

      // The second event is accepted while the first read is still the latest generation. Its
      // immutable origin must not be consumed by the first read before this debounce is released.
      requireNotNull(listener.get()).invoke(ReqwsProjectModelChangeKind.ORDINARY)
      awaitCondition("second ordinary-event debounce") { debounceCount.get() == 2 }
      allowFirstFollowUpOffer.countDown()
      awaitCondition("first verify-only follow-up apply") { applyTriggers.size == 2 }
      assertTrue(debounceReleases.trySend(Unit).isSuccess)
      awaitCondition("second verify-only follow-up apply") { applyTriggers.size == 3 }

      Thread.sleep(NO_CHURN_WINDOW_MILLIS)
      assertEquals(
        listOf(
          SyncTrigger.AUTOMATIC,
          SyncTrigger.PROJECT_MODEL_FOLLOW_UP,
          SyncTrigger.PROJECT_MODEL_FOLLOW_UP,
        ),
        applyTriggers,
      )
      assertEquals(2, debounceCount.get())
    } finally {
      allowFirstFollowUpOffer.countDown()
      debounceReleases.close()
      service.dispose()
      scope.cancel()
    }
  }

  fun testFailedFollowUpReadDoesNotSuppressNotificationForANewerManifestDigest() =
    verifyFailedFollowUpReadDoesNotSuppressNotificationForANewerManifestDigest()

  private fun verifyFailedFollowUpReadDoesNotSuppressNotificationForANewerManifestDigest() {
    val root = writeValidManifest()
    val manifest = ReqwsProjectDetector.manifestPath(root)
    val originalManifest = Files.readString(manifest)
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val applyTriggers = CopyOnWriteArrayList<SyncTrigger>()
    val listener = AtomicReference<((ReqwsProjectModelChangeKind) -> Unit)?>()
    val debounceReleases = Channel<Unit>(Channel.UNLIMITED)
    val service = ReqwsProjectService.createForTest(
      project = project,
      coroutineScope = scope,
      runtimeOverrides = projectModelChangeRuntimeOverrides(
        candidateApplier = SyncCandidateApplier { candidate ->
          applyTriggers.add(candidate.trigger)
        },
        registrar = ReqwsProjectModelChangeRegistrar { callback ->
          listener.set(callback)
          AutoCloseable {}
        },
        debounceWaiter = ReqwsProjectModelChangeDebounceWaiter {
          debounceReleases.receive()
        },
      ),
    )
    try {
      awaitSuccessfulCompletion(
        requireNotNull(service.refreshAutomatically()),
        "initial projection before failed follow-up read",
      )
      awaitCondition("initial apply before failed follow-up read") { applyTriggers.size == 1 }

      Files.writeString(manifest, "{")
      requireNotNull(listener.get()).invoke(ReqwsProjectModelChangeKind.ORDINARY)
      assertTrue(debounceReleases.trySend(Unit).isSuccess)
      awaitStableLifecycle(
        service,
        ReqwsLifecycleState.ERROR,
        "failed verify-only follow-up read",
      )

      Files.writeString(
        manifest,
        originalManifest.replace(
          "2026-08-14T00:00:00.000Z",
          "2026-08-15T00:00:00.000Z",
        ),
      )
      awaitSuccessfulCompletion(
        requireNotNull(service.refreshAutomatically()),
        "automatic recovery with a newer manifest digest",
      )
      awaitCondition("newer manifest apply") { applyTriggers.size == 2 }

      assertEquals(
        listOf(SyncTrigger.AUTOMATIC, SyncTrigger.AUTOMATIC),
        applyTriggers,
      )
    } finally {
      debounceReleases.close()
      service.dispose()
      scope.cancel()
    }
  }

  fun testDisposeWinningBeforeCandidateCommitPreventsDurableDigestAdvance() =
    verifyDisposeWinningBeforeCandidateCommitPreventsDurableDigestAdvance()

  private fun verifyDisposeWinningBeforeCandidateCommitPreventsDurableDigestAdvance() {
    writeValidManifest()
    val rootJob = SupervisorJob()
    val scope = CoroutineScope(rootJob + Dispatchers.Default)
    val commitEntered = CountDownLatch(1)
    val allowCommitGate = CountDownLatch(1)
    val persistence = project.service<ReqwsSyncPersistence>()
    persistence.loadState(ReqwsSyncPersistence.Data())
    val service = ReqwsProjectService.createForTest(
      project = project,
      coroutineScope = scope,
      runtimeOverrides = ReqwsProjectServiceRuntimeOverrides(
        trustGate = ReqwsTrustGate { true },
        candidateApplier = SyncCandidateApplier {},
        vcsChangeRegistrar = ReqwsVcsChangeRegistrar { AutoCloseable {} },
        projectModelChangeRegistrar = ReqwsProjectModelChangeRegistrar { AutoCloseable {} },
        vcsInspector = ReqwsVcsInspector { VcsRootInspection(emptyList(), emptyList()) },
        beforeCandidateCommit = {
          commitEntered.countDown()
          check(allowCommitGate.await(5, TimeUnit.SECONDS)) {
            "test did not release the candidate commit gate"
          }
        },
        manifestWatcherFactory = ReqwsManifestWatcherFactory { _, _, _, _ -> Disposable {} },
      ),
    )
    try {
      awaitSuccessfulCompletion(
        requireNotNull(service.refreshAutomatically()),
        "refresh racing dispose at the candidate commit boundary",
      )
      assertTrue(
        "candidate did not reach the commit boundary",
        commitEntered.await(5, TimeUnit.SECONDS),
      )

      service.dispose()
      assertSame(ReqwsProjectState.DISPOSED, service.state)
      allowCommitGate.countDown()
      runBlocking {
        rootJob.children.forEach { child -> child.join() }
      }

      assertNull(persistence.lastAppliedDigest())
    } finally {
      allowCommitGate.countDown()
      service.dispose()
      scope.cancel()
    }
  }

  fun testWatcherThatFinishesConstructionAfterDisposeIsClosed() {
    val configuredRoot = Path.of(requireNotNull(project.basePath)).toAbsolutePath().normalize()
    Files.createDirectories(configuredRoot)
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val factoryEntered = CountDownLatch(1)
    val allowFactoryReturn = CountDownLatch(1)
    val closeCount = AtomicInteger(0)
    val service = ReqwsProjectService.createForTest(
      project = project,
      coroutineScope = scope,
      runtimeOverrides = ReqwsProjectServiceRuntimeOverrides(
        manifestWatcherFactory = ReqwsManifestWatcherFactory { _, _, _, _ ->
          factoryEntered.countDown()
          check(allowFactoryReturn.await(5, TimeUnit.SECONDS)) {
            "test did not release manifest watcher construction"
          }
          Disposable { closeCount.incrementAndGet() }
        },
      ),
    )
    val refresh = requireNotNull(service.refreshAutomatically())

    try {
      assertTrue(
        "manifest watcher construction did not start",
        factoryEntered.await(5, TimeUnit.SECONDS),
      )
      service.dispose()
      assertSame(ReqwsProjectState.DISPOSED, service.state)

      allowFactoryReturn.countDown()
      awaitSuccessfulCompletion(
        job = refresh,
        description = "refresh racing manifest watcher construction with dispose",
      )

      assertEquals(1, closeCount.get())
      assertNull(service.refreshAutomatically())
    } finally {
      allowFactoryReturn.countDown()
      service.dispose()
      scope.cancel()
    }
    assertEquals(1, closeCount.get())
  }

  fun testThrowingWatcherDoesNotPreventTerminalDisposeOrRepeatDispose() {
    val configuredRoot = Path.of(requireNotNull(project.basePath)).toAbsolutePath().normalize()
    Files.createDirectories(configuredRoot)
    val expectedFailure = IllegalStateException("synthetic watcher dispose failure")
    val closeCount = AtomicInteger(0)
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val service = ReqwsProjectService.createForTest(
      project = project,
      coroutineScope = scope,
      runtimeOverrides = ReqwsProjectServiceRuntimeOverrides(
        manifestWatcherFactory = ReqwsManifestWatcherFactory { _, _, _, _ ->
          Disposable {
            closeCount.incrementAndGet()
            throw expectedFailure
          }
        },
      ),
    )

    try {
      awaitSuccessfulCompletion(
        job = requireNotNull(service.refreshAutomatically()),
        description = "refresh installing a throwing watcher",
      )

      val failure = captureFailure(service::dispose)
      assertSame(expectedFailure, failure)
      assertSame(ReqwsProjectState.DISPOSED, service.state)
      assertNull(service.refresh())
      assertNull(service.refreshAutomatically())
      assertEquals(1, closeCount.get())

      service.dispose()
      assertEquals(1, closeCount.get())
    } finally {
      try {
        service.dispose()
      } finally {
        scope.cancel()
      }
    }
  }

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
    // The production manifest watcher can legitimately move the pooled platform-test project out
    // of INACTIVE before this test attaches. This test owns only the terminal publication contract.
    assertTrue(observed.first() != ReqwsLifecycleState.DISPOSED)
    assertEquals(ReqwsLifecycleState.DISPOSED, observed.last())
    assertEquals(1, observed.count { it == ReqwsLifecycleState.DISPOSED })
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

  /** Exercises the production startup trigger once without calling either refresh API in tests. */
  private fun executeStartupActivity(
    service: ReqwsProjectService,
    availabilityChanges: MutableList<Boolean>? = null,
  ) {
    val activity = ReqwsStartupActivity(
      serviceForProject = { service },
      bindAvailability = { _, boundService ->
        val controller = ReqwsToolWindowAvailabilityController(
          isProjectDisposed = { false },
          isToolWindowDisposed = { false },
          dispatchOnEdt = { action -> action() },
          setAvailable = { available -> availabilityChanges?.add(available) },
        )
        Disposer.register(testRootDisposable, controller)
        controller.bind(boundService)
      },
    )
    runBlocking { activity.execute(project) }
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

  private fun captureFailure(action: () -> Unit): Throwable {
    var captured: Throwable? = null
    try {
      action()
    } catch (failure: Throwable) {
      captured = failure
    }
    return requireNotNull(captured) { "expected action to fail" }
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

  private fun projectModelChangeRuntimeOverrides(
    candidateApplier: SyncCandidateApplier<ManifestSnapshot>,
    registrar: ReqwsProjectModelChangeRegistrar,
    debounceWaiter: ReqwsProjectModelChangeDebounceWaiter,
    beforeCandidateOffer: (() -> Unit)? = null,
  ): ReqwsProjectServiceRuntimeOverrides = ReqwsProjectServiceRuntimeOverrides(
    trustGate = ReqwsTrustGate { true },
    candidateApplier = candidateApplier,
    vcsChangeRegistrar = ReqwsVcsChangeRegistrar { AutoCloseable {} },
    projectModelChangeRegistrar = registrar,
    projectModelChangeDebounceWaiter = debounceWaiter,
    beforeCandidateOffer = beforeCandidateOffer,
    vcsInspector = ReqwsVcsInspector { VcsRootInspection(emptyList(), emptyList()) },
    manifestWatcherFactory = ReqwsManifestWatcherFactory { _, _, _, _ -> Disposable {} },
  )

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

  private fun writeValidManifestWithRepository(): Path {
    val root = writeValidManifest()
    val repositoryPath = root.resolve("repo-a")
    Files.createDirectories(repositoryPath)
    val manifest = ReqwsProjectDetector.manifestPath(root)
    val repositoryJson = """
      [
        {
          "catalogRepositoryId": "service_repo_a",
          "name": "repo-a",
          "url": "https://example.test/team/repo-a.git",
          "defaultBranch": "main",
          "relativePath": "repo-a"
        }
      ]
    """.trimIndent()
    Files.writeString(
      manifest,
      Files.readString(manifest).replace("\"repositories\": []", "\"repositories\": $repositoryJson"),
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
