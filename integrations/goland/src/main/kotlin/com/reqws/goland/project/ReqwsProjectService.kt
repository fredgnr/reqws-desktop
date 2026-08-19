package com.reqws.goland.project

import com.intellij.ide.trustedProjects.TrustedProjects
import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.reqws.goland.manifest.ManifestErrorCode
import com.reqws.goland.manifest.ManifestReader
import com.reqws.goland.manifest.ManifestSnapshot
import com.reqws.goland.sync.LatestWinsSyncCoordinator
import com.reqws.goland.sync.SyncCandidate
import com.reqws.goland.sync.SyncCandidateApplier
import com.reqws.goland.sync.SyncCoordinatorEvent
import com.reqws.goland.sync.SyncCoordinatorObserver
import com.reqws.goland.sync.SyncFailureStage
import com.reqws.goland.sync.SyncTrigger
import com.reqws.goland.vcs.ReqwsVcsConfigurationMonitor
import com.reqws.goland.vcs.ReqwsVcsDiagnosticsService
import com.reqws.goland.vcs.VcsRootInspection
import com.reqws.goland.watch.ManifestSyncRequest
import com.reqws.goland.watch.ManifestVfsWatcher
import java.nio.file.Path
import java.util.LinkedHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch

@Service(Service.Level.PROJECT)
class ReqwsProjectService private constructor(
  private val project: Project,
  private val coroutineScope: CoroutineScope,
  private val runtimeOverrides: ReqwsProjectServiceRuntimeOverrides?,
) : Disposable {
  constructor(project: Project, coroutineScope: CoroutineScope) : this(
    project = project,
    coroutineScope = coroutineScope,
    runtimeOverrides = null,
  )

  private val disposed = AtomicBoolean(false)
  private val readRequests = SyncReadRequestTracker()
  private val statePublisher = TerminalStatePublisher(
    initialState = ReqwsProjectState.INACTIVE,
    isTerminal = { state -> state.lifecycle == ReqwsLifecycleState.DISPOSED },
  )
  private val watcherRef = AtomicReference<ManifestVfsWatcher?>()
  private val applyingState = AtomicReference<CandidateState?>()
  private val candidateLock = Any()
  private val candidateStates = LinkedHashMap<String, ReqwsProjectState>()
  private val persistence: ReqwsSyncPersistence
    get() = project.service()
  private val trustGate = runtimeOverrides?.trustGate
    ?: ReqwsTrustGate { TrustedProjects.isProjectTrusted(project) }
  private val loader = ReqwsProjectLoadEngine(
    manifestReader = ManifestReader(),
    trustGate = trustGate,
  )
  private val projectionApplier by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
    ReqwsProjectionApplier.forProject(
      project = project,
      isServiceDisposed = disposed::get,
    )
  }
  private val coordinator = LatestWinsSyncCoordinator(
    scope = coroutineScope,
    applier = runtimeOverrides?.candidateApplier
      ?: SyncCandidateApplier<ManifestSnapshot> { candidate ->
        projectionApplier.apply(candidate.value)
      },
    observer = SyncCoordinatorObserver(::onCoordinatorEvent),
  )
  private val trustMonitor = TrustTransitionMonitor(
    scope = coroutineScope,
    probe = TrustStateProbe(trustGate::isTrusted),
    action = TrustedTransitionAction {
      // SAFE_MODE_BLOCKED acceptance already arms the forced intent. The poll only wakes a read;
      // keeping that read automatic prevents a late poll from arming a second replay after an
      // earlier automatic read has already consumed the transition intent.
      requestRefresh(SyncTrigger.AUTOMATIC)
    },
    pollMillis = runtimeOverrides?.trustPollMillis
      ?: TrustTransitionMonitor.DEFAULT_POLL_MILLIS,
    waiter = runtimeOverrides?.trustPollWaiter
      ?: TrustPollWaiter { delay(it) },
  )
  private val vcsChangeLifecycleLock = Any()
  private var vcsChangeMonitoringState = VcsChangeMonitoringState.NOT_STARTED
  private var vcsChangeRegistrationVersion = 0L
  private var vcsChangeMonitoringEpoch: VcsChangeMonitoringEpoch? = null
  private var vcsChangeRegistration: AutoCloseable? = null
  private var vcsChangeMonitoringAccepted = false
  private val vcsChangeRegistrar = runtimeOverrides?.vcsChangeRegistrar
    ?: ReqwsVcsChangeRegistrar { listener ->
      project.service<ReqwsVcsConfigurationMonitor>()
        .addExternalChangeListener { listener() }
    }
  private val vcsInspector = runtimeOverrides?.vcsInspector
    ?: ReqwsVcsInspector { snapshot ->
      project.service<ReqwsVcsDiagnosticsService>().inspect(snapshot)
    }

  val state: ReqwsProjectState
    get() = statePublisher.state

  /** Sync Now bypasses VFS debounce but enters the same serialized coordinator. */
  fun refresh(): Job? = requestRefresh(SyncTrigger.MANUAL)

  /** Startup, VFS, VCS, and Tool Window lifecycle refreshes retain automatic no-op semantics. */
  internal fun refreshAutomatically(): Job? = requestRefresh(SyncTrigger.AUTOMATIC)

  /** Starts callback registration only after a current read has produced a valid snapshot. */
  internal suspend fun startVcsChangeMonitoring(
    registrar: ReqwsVcsChangeRegistrar = vcsChangeRegistrar,
  ): VcsChangeMonitoringStart {
    val preparation = reserveVcsChangeMonitoringPreparation()
      ?: return VcsChangeMonitoringStart.UNAVAILABLE
    val start = startReservedVcsChangeMonitoring(preparation, registrar)
    if (
      start == VcsChangeMonitoringStart.UNAVAILABLE ||
      !completeVcsChangeMonitoringPreparation(preparation, accepted = true)
    ) {
      return VcsChangeMonitoringStart.UNAVAILABLE
    }
    return start
  }

  /**
   * Reserves a registration epoch without invoking the platform registrar. Callers perform this
   * small state transition at the latest-read boundary, then install or join the listener outside
   * the read-selection lock. A newer valid read adopts the epoch; a newer invalid read revokes it.
   */
  private fun reserveVcsChangeMonitoringPreparation(): VcsChangeMonitoringPreparation? =
    synchronized(vcsChangeLifecycleLock) {
      if (disposed.get() || project.isDisposed) return@synchronized null
      when (vcsChangeMonitoringState) {
        VcsChangeMonitoringState.DISPOSED -> {
          return@synchronized null
        }
        VcsChangeMonitoringState.NOT_STARTED -> {
          vcsChangeRegistrationVersion += 1
          vcsChangeMonitoringEpoch = VcsChangeMonitoringEpoch(
            registrationVersion = vcsChangeRegistrationVersion,
          )
          vcsChangeMonitoringState = VcsChangeMonitoringState.RESERVED
          vcsChangeMonitoringAccepted = false
        }
        VcsChangeMonitoringState.RESERVED,
        VcsChangeMonitoringState.STARTING,
        -> {
          // A newer valid read joins the provisional epoch and can complete its installation.
        }
        VcsChangeMonitoringState.STARTED -> Unit
      }

      VcsChangeMonitoringPreparation(
        epoch = requireNotNull(vcsChangeMonitoringEpoch),
      )
    }

  private suspend fun startReservedVcsChangeMonitoring(
    preparation: VcsChangeMonitoringPreparation,
    registrar: ReqwsVcsChangeRegistrar = vcsChangeRegistrar,
  ): VcsChangeMonitoringStart {
    val epoch = preparation.epoch
    while (true) {
      var attempt: CompletableDeferred<Boolean>? = null
      val shouldRegister = synchronized(vcsChangeLifecycleLock) {
        if (
          disposed.get() ||
          project.isDisposed ||
          vcsChangeMonitoringEpoch !== epoch
        ) {
          return@synchronized null
        }
        when (vcsChangeMonitoringState) {
          VcsChangeMonitoringState.STARTED -> return VcsChangeMonitoringStart.ALREADY_STARTED
          VcsChangeMonitoringState.RESERVED -> {
            vcsChangeMonitoringState = VcsChangeMonitoringState.STARTING
            attempt = epoch.startAttempt
            true
          }
          VcsChangeMonitoringState.STARTING -> {
            attempt = epoch.startAttempt
            false
          }
          VcsChangeMonitoringState.NOT_STARTED,
          VcsChangeMonitoringState.DISPOSED,
          -> null
        }
      }
      if (shouldRegister == null) return VcsChangeMonitoringStart.UNAVAILABLE
      val currentAttempt = requireNotNull(attempt)
      if (!shouldRegister) {
        if (!currentAttempt.await()) continue
        return synchronized(vcsChangeLifecycleLock) {
          if (
            vcsChangeMonitoringEpoch === epoch &&
            vcsChangeMonitoringState == VcsChangeMonitoringState.STARTED
          ) {
            VcsChangeMonitoringStart.ALREADY_STARTED
          } else {
            VcsChangeMonitoringStart.UNAVAILABLE
          }
        }
      }

      val registration = try {
        registrar.addExternalChangeListener {
          handleExternalVcsConfigurationChange(epoch.registrationVersion)
        }
      } catch (failure: Throwable) {
        synchronized(vcsChangeLifecycleLock) {
          if (
            vcsChangeMonitoringEpoch === epoch &&
            vcsChangeMonitoringState == VcsChangeMonitoringState.STARTING &&
            epoch.startAttempt === currentAttempt
          ) {
            vcsChangeMonitoringState = VcsChangeMonitoringState.RESERVED
            epoch.startAttempt = CompletableDeferred()
            vcsChangeMonitoringAccepted = false
          }
        }
        currentAttempt.complete(false)
        throw failure
      }

      val committed = synchronized(vcsChangeLifecycleLock) {
        if (
          disposed.get() ||
          project.isDisposed ||
          vcsChangeMonitoringEpoch !== epoch ||
          vcsChangeMonitoringState != VcsChangeMonitoringState.STARTING ||
          epoch.startAttempt !== currentAttempt
        ) {
          false
        } else {
          vcsChangeRegistration = registration
          vcsChangeMonitoringState = VcsChangeMonitoringState.STARTED
          vcsChangeMonitoringAccepted = false
          true
        }
      }
      currentAttempt.complete(committed)
      if (!committed) {
        closeVcsChangeRegistration(registration)
        return VcsChangeMonitoringStart.UNAVAILABLE
      }
      return VcsChangeMonitoringStart.STARTED_NOW
    }
  }

  /**
   * Keeps a provisional registration alive while a valid read crosses its post-registration
   * inspection and latest-generation gate. A newer valid read can adopt the same registration;
   * a newer inactive/error read can revoke an as-yet unaccepted registration immediately.
   */
  private fun completeVcsChangeMonitoringPreparation(
    preparation: VcsChangeMonitoringPreparation,
    accepted: Boolean,
  ): Boolean {
    if (!preparation.completed.compareAndSet(false, true)) return false
    val acceptedCurrentRegistration = synchronized(vcsChangeLifecycleLock) {
      if (
        vcsChangeMonitoringState != VcsChangeMonitoringState.STARTED ||
        vcsChangeMonitoringEpoch !== preparation.epoch
      ) {
        false
      } else {
        if (accepted) {
          vcsChangeMonitoringAccepted = true
          true
        } else {
          false
        }
      }
    }
    return acceptedCurrentRegistration
  }

  /** Detaches only a provisional listener; external completion and close run outside both locks. */
  private fun revokeUnacceptedVcsChangeMonitoring(): VcsChangeMonitoringRevocation? =
    synchronized(vcsChangeLifecycleLock) {
      if (
        vcsChangeMonitoringState in setOf(
          VcsChangeMonitoringState.RESERVED,
          VcsChangeMonitoringState.STARTING,
          VcsChangeMonitoringState.STARTED,
        ) &&
        !vcsChangeMonitoringAccepted
      ) {
        val currentEpoch = requireNotNull(vcsChangeMonitoringEpoch)
        val revocation = VcsChangeMonitoringRevocation(
          startAttempt = currentEpoch.startAttempt,
          registration = vcsChangeRegistration,
        )
        vcsChangeMonitoringState = VcsChangeMonitoringState.NOT_STARTED
        vcsChangeMonitoringEpoch = null
        vcsChangeRegistration = null
        vcsChangeMonitoringAccepted = false
        revocation
      } else {
        null
      }
    }

  private fun finishVcsChangeMonitoringRevocation(
    revocation: VcsChangeMonitoringRevocation?,
  ) {
    revocation ?: return
    revocation.startAttempt.complete(false)
    closeVcsChangeRegistration(revocation.registration)
  }

  private fun handleExternalVcsConfigurationChange(registrationVersion: Long): Job? {
    if (disposed.get() || project.isDisposed) return null
    val started = synchronized(vcsChangeLifecycleLock) {
      vcsChangeMonitoringState == VcsChangeMonitoringState.STARTED &&
        vcsChangeRegistrationVersion == registrationVersion
    }
    // A registrar may invoke its callback synchronously. The mandatory post-registration
    // inspection covers every change before STARTED, without recursively starting a newer
    // manifest read while the first valid candidate is still being accepted.
    if (!started) return null
    // Once active, VCS changes retain the existing full automatic refresh semantics: a clean
    // model baseline NoOps after publishing fresh diagnostics, while an already-dirty baseline
    // can converge through the normal coordinator.
    return coroutineScope.launch(Dispatchers.IO) {
      runtimeOverrides?.beforeVcsCallbackRefresh?.invoke()
      requestRefresh(
        trigger = SyncTrigger.AUTOMATIC,
        requiredVcsRegistrationVersion = registrationVersion,
      )?.join()
    }
  }

  private fun requestRefresh(
    trigger: SyncTrigger,
    requiredVcsRegistrationVersion: Long? = null,
  ): Job? {
    if (disposed.get() || project.isDisposed) return null
    val request = if (requiredVcsRegistrationVersion == null) {
      readRequests.begin(trigger)
    } else {
      readRequests.beginIf(trigger) {
        synchronized(vcsChangeLifecycleLock) {
          vcsChangeMonitoringState == VcsChangeMonitoringState.STARTED &&
            vcsChangeRegistrationVersion == requiredVcsRegistrationVersion
        }
      } ?: return null
    }
    var job: Job? = null
    val cleanup = {
      var revocation: VcsChangeMonitoringRevocation? = null
      readRequests.runIfLatest(request) {
        revocation = revokeUnacceptedVcsChangeMonitoring()
      }
      finishVcsChangeMonitoringRevocation(revocation)
    }
    return try {
      val projectRoot = ReqwsProjectDetector.projectRoot(project)
      val observedVcsRegistrationVersion = currentStartedVcsRegistrationVersion()
      val launched = coroutineScope.launch(Dispatchers.IO, start = CoroutineStart.LAZY) {
        projectRoot
          ?.let(ReqwsProjectDetector::canonicalProjectRoot)
          ?.let(::ensureWatcher)
        val previous = state
        val loaded = loadWithRetry(projectRoot, previous)
        currentCoroutineContext().ensureActive()
        if (disposed.get()) return@launch
        acceptLoadedState(loaded, request, observedVcsRegistrationVersion)
      }
      job = launched
      // The handler is installed before READING is published, so every exit after generation
      // creation—including cancellation before the coroutine body starts—releases an unaccepted
      // listener owned by the latest request. Accepted lifecycle registrations remain open.
      launched.invokeOnCompletion { cleanup() }
      publish(
        state.copy(
          lifecycle = ReqwsLifecycleState.READING,
          lastError = null,
        ),
      )
      launched.start()
      launched
    } catch (failure: Throwable) {
      job?.cancel()
      cleanup()
      throw failure
    }
  }

  private suspend fun loadWithRetry(
    projectRoot: Path?,
    previous: ReqwsProjectState,
  ): ReqwsProjectState {
    var loaded = loader.load(projectRoot, previous)
    repeat(MANIFEST_RETRY_COUNT - 1) {
      if (!loaded.isRetryableManifestGap()) {
        return loaded.withPersistedDigest().withVcsInspection()
      }
      delay(MANIFEST_RETRY_DELAY_MILLIS)
      loaded = loader.load(projectRoot, previous)
    }
    return loaded.withPersistedDigest().withVcsInspection()
  }

  private suspend fun acceptLoadedState(
    loaded: ReqwsProjectState,
    request: SyncReadRequest,
    observedVcsRegistrationVersion: Long?,
  ) {
    when (loaded.lifecycle) {
      ReqwsLifecycleState.INACTIVE -> {
        var revocation: VcsChangeMonitoringRevocation? = null
        try {
          readRequests.runIfLatest(request) {
            trustMonitor.cancelPending()
            revocation = revokeUnacceptedVcsChangeMonitoring()
            publish(loaded)
          }
        } finally {
          finishVcsChangeMonitoringRevocation(revocation)
        }
      }
      ReqwsLifecycleState.SAFE_MODE_BLOCKED -> {
        var monitoring: VcsChangeMonitoringPreparation? = null
        if (
          !readRequests.runIfLatestAndArmReconcile(
            request = request,
            trigger = SyncTrigger.TRUST_TRANSITION,
          ) {
            monitoring = reserveVcsChangeMonitoringPreparation()
          }
        ) {
          return
        }
        val reservedMonitoring = monitoring ?: return
        val prepared = prepareValidState(
          loaded = loaded,
          monitoring = reservedMonitoring,
          observedVcsRegistrationVersion = observedVcsRegistrationVersion,
        ) ?: return
        currentCoroutineContext().ensureActive()
        val accepted = readRequests.runIfLatest(request) {
          if (completeVcsChangeMonitoringPreparation(prepared.monitoring, accepted = true)) {
            publish(prepared.state)
            trustMonitor.awaitTrusted()
          }
        }
        if (!accepted) {
          completeVcsChangeMonitoringPreparation(prepared.monitoring, accepted = false)
        }
      }
      ReqwsLifecycleState.ERROR -> {
        var revocation: VcsChangeMonitoringRevocation? = null
        try {
          readRequests.runIfLatest(request) { trigger ->
            trustMonitor.cancelPending()
            revocation = revokeUnacceptedVcsChangeMonitoring()
            coordinator.offerReadFailure(
              cause = ReadStateFailure(loaded),
              trigger = trigger,
              digestSha256 = loaded.lastError?.digestSha256,
            )
          }
        } finally {
          finishVcsChangeMonitoringRevocation(revocation)
        }
      }
      ReqwsLifecycleState.SYNCHRONIZED,
      ReqwsLifecycleState.DEGRADED,
      -> {
        val snapshot = requireNotNull(loaded.snapshot)
        var monitoring: VcsChangeMonitoringPreparation? = null
        val reserved = readRequests.runIfLatest(request) {
          monitoring = reserveVcsChangeMonitoringPreparation()
        }
        if (!reserved) {
          return
        }
        val reservedMonitoring = monitoring ?: return
        val prepared = prepareValidState(
          loaded = loaded,
          monitoring = reservedMonitoring,
          observedVcsRegistrationVersion = observedVcsRegistrationVersion,
        ) ?: return
        currentCoroutineContext().ensureActive()
        val accepted = readRequests.offerCandidateIfLatest(request) { trigger ->
          trustMonitor.cancelPending()
          rememberCandidate(snapshot.digestSha256, prepared.state)
          val offered = coordinator.offer(SyncCandidate(snapshot.digestSha256, snapshot), trigger)
          offered && completeVcsChangeMonitoringPreparation(
            prepared.monitoring,
            accepted = true,
          )
        }
        if (!accepted) {
          completeVcsChangeMonitoringPreparation(prepared.monitoring, accepted = false)
        }
      }
      ReqwsLifecycleState.READING,
      ReqwsLifecycleState.SYNCHRONIZING,
      ReqwsLifecycleState.DISPOSED,
      -> Unit
    }
  }

  private fun onCoordinatorEvent(event: SyncCoordinatorEvent) {
    if (disposed.get()) return
    when (event) {
      is SyncCoordinatorEvent.Applying -> {
        val candidate = takeCandidate(event.digestSha256)
        applyingState.set(candidate)
        publish(
          (candidate?.state ?: state).copy(
            lifecycle = ReqwsLifecycleState.SYNCHRONIZING,
            lastError = null,
          ),
        )
      }
      is SyncCoordinatorEvent.Applied -> {
        val candidate = takeApplying(event.digestSha256)
        publish(
          (candidate?.state ?: state).afterSuccessfulProjection(
            persistence.lastAppliedDigest() ?: event.digestSha256,
          ),
        )
      }
      is SyncCoordinatorEvent.NoOp -> {
        val candidate = takeCandidate(event.digestSha256)
        val loaded = candidate?.state ?: state
        publish(
          loaded.afterSuccessfulProjection(
            persistence.lastAppliedDigest() ?: event.digestSha256,
          ),
        )
      }
      is SyncCoordinatorEvent.Failed -> handleCoordinatorFailure(event)
    }
  }

  private fun handleCoordinatorFailure(event: SyncCoordinatorEvent.Failed) {
    if (event.stage == SyncFailureStage.READ && event.cause is ReadStateFailure) {
      publish(event.cause.failedState)
      return
    }

    val candidate = event.digestSha256?.let(::takeApplying)
      ?: event.digestSha256?.let(::takeCandidate)
    val projectionFailure = event.cause as? ReqwsProjectionApplyException
    if (projectionFailure?.stableCode == ReqwsStableErrorCode.SAFE_MODE_BLOCKED) {
      publish(
        (candidate?.state ?: state).copy(
          lifecycle = ReqwsLifecycleState.SAFE_MODE_BLOCKED,
          lastError = null,
        ),
      )
      trustMonitor.awaitTrusted()
      return
    }

    val stableCode = projectionFailure?.stableCode
      ?: ReqwsStableErrorCode.PROJECT_MODEL_APPLY_FAILED
    publish(
      (candidate?.state ?: state).copy(
        lifecycle = if (projectionFailure?.degraded == true) {
          ReqwsLifecycleState.DEGRADED
        } else {
          ReqwsLifecycleState.ERROR
        },
        lastError = ReqwsProjectError(
          code = stableCode,
          digestSha256 = event.digestSha256,
        ),
      ),
    )
  }

  private fun ReqwsProjectState.withPersistedDigest(): ReqwsProjectState = copy(
    lastAppliedDigest = persistence.lastAppliedDigest() ?: lastAppliedDigest,
  )

  private fun ReqwsProjectState.withVcsInspection(): ReqwsProjectState {
    val currentSnapshot = snapshot ?: return copy(vcsInspection = null)
    return copy(
      vcsInspection = vcsInspector.inspect(currentSnapshot),
    )
  }

  /**
   * Arms the VCS observer only for a valid manifest state. The first registration is followed by
   * a second inspection of these exact snapshot bytes: changes before listener installation are
   * therefore observed by the second read, and later changes are covered by the listener. The
   * caller performs both latest-read gates. Only the registration epoch is reserved at the first
   * gate; platform registration and inspection stay outside that lock so an EDT Sync Now request
   * never waits for VCS IO.
   */
  private suspend fun prepareValidState(
    loaded: ReqwsProjectState,
    monitoring: VcsChangeMonitoringPreparation,
    observedVcsRegistrationVersion: Long?,
  ): PreparedValidState? {
    val snapshot = requireNotNull(loaded.snapshot) {
      "VCS monitoring requires a valid manifest snapshot"
    }
    return try {
      val start = startReservedVcsChangeMonitoring(monitoring)
      if (start == VcsChangeMonitoringStart.UNAVAILABLE) {
        completeVcsChangeMonitoringPreparation(monitoring, accepted = false)
        return null
      }
      val registeredAfterReadStarted =
        observedVcsRegistrationVersion != monitoring.epoch.registrationVersion
      PreparedValidState(
        state = if (
          start == VcsChangeMonitoringStart.STARTED_NOW ||
          registeredAfterReadStarted
        ) {
          loaded.copy(
            vcsInspection = vcsInspector.inspect(snapshot),
          )
        } else {
          loaded
        },
        monitoring = monitoring,
      )
    } catch (failure: Throwable) {
      completeVcsChangeMonitoringPreparation(monitoring, accepted = false)
      throw failure
    }
  }

  private fun currentStartedVcsRegistrationVersion(): Long? =
    synchronized(vcsChangeLifecycleLock) {
      vcsChangeRegistrationVersion.takeIf {
        vcsChangeMonitoringState == VcsChangeMonitoringState.STARTED
      }
    }

  private fun ReqwsProjectState.isRetryableManifestGap(): Boolean =
    lifecycle == ReqwsLifecycleState.ERROR &&
      lastError?.code in setOf(
        ManifestErrorCode.MANIFEST_NOT_FOUND.name,
        ManifestErrorCode.MANIFEST_NOT_REGULAR_FILE.name,
      )

  private fun ensureWatcher(projectRoot: Path) {
    if (watcherRef.get() != null || disposed.get()) return
    val watcher = ManifestVfsWatcher(
      project = project,
      manifestPath = ReqwsProjectDetector.manifestPath(projectRoot),
      coroutineScope = coroutineScope,
      syncRequest = ManifestSyncRequest { requestRefresh(SyncTrigger.AUTOMATIC) },
    )
    if (!watcherRef.compareAndSet(null, watcher)) watcher.dispose()
  }

  private fun rememberCandidate(digest: String, loaded: ReqwsProjectState) {
    synchronized(candidateLock) {
      candidateStates[digest] = loaded
      while (candidateStates.size > MAX_PENDING_CANDIDATES) {
        val eldest = candidateStates.entries.firstOrNull() ?: break
        candidateStates.remove(eldest.key)
      }
    }
  }

  private fun takeCandidate(digest: String): CandidateState? = synchronized(candidateLock) {
    candidateStates.remove(digest)?.let { CandidateState(digest, it) }
  }

  private fun takeApplying(digest: String): CandidateState? {
    val active = applyingState.get()
    return if (active?.digestSha256 == digest && applyingState.compareAndSet(active, null)) {
      active
    } else {
      null
    }
  }

  fun addListener(listener: (ReqwsProjectState) -> Unit): AutoCloseable {
    if (disposed.get()) {
      listener(ReqwsProjectState.DISPOSED)
      return AutoCloseable {}
    }
    return statePublisher.addListener(listener)
  }

  private fun publish(next: ReqwsProjectState) {
    statePublisher.publish(next)
  }

  private fun closeVcsChangeRegistration(registration: AutoCloseable?) {
    try {
      registration?.close()
    } catch (_: Exception) {
      // Project disposal must still tear down the remaining lifecycle-owned resources.
    }
  }

  override fun dispose() {
    if (!disposed.compareAndSet(false, true)) return
    try {
      statePublisher.publish(ReqwsProjectState.DISPOSED)
    } finally {
      val revocation = synchronized(vcsChangeLifecycleLock) {
        vcsChangeMonitoringState = VcsChangeMonitoringState.DISPOSED
        val currentEpoch = vcsChangeMonitoringEpoch
        val currentRegistration = vcsChangeRegistration
        vcsChangeMonitoringEpoch = null
        vcsChangeRegistration = null
        vcsChangeMonitoringAccepted = false
        currentEpoch?.let {
          VcsChangeMonitoringRevocation(
            startAttempt = it.startAttempt,
            registration = currentRegistration,
          )
        }
      }
      finishVcsChangeMonitoringRevocation(revocation)
      readRequests.invalidate()
      trustMonitor.close()
      watcherRef.getAndSet(null)?.dispose()
      coordinator.close()
      synchronized(candidateLock) { candidateStates.clear() }
      applyingState.set(null)
    }
  }

  private data class CandidateState(
    val digestSha256: String,
    val state: ReqwsProjectState,
  )

  private data class PreparedValidState(
    val state: ReqwsProjectState,
    val monitoring: VcsChangeMonitoringPreparation,
  )

  private data class VcsChangeMonitoringPreparation(
    val epoch: VcsChangeMonitoringEpoch,
    val completed: AtomicBoolean = AtomicBoolean(false),
  )

  private class VcsChangeMonitoringEpoch(
    val registrationVersion: Long,
  ) {
    var startAttempt: CompletableDeferred<Boolean> = CompletableDeferred()
  }

  private data class VcsChangeMonitoringRevocation(
    val startAttempt: CompletableDeferred<Boolean>,
    val registration: AutoCloseable?,
  )

  private enum class VcsChangeMonitoringState {
    NOT_STARTED,
    RESERVED,
    STARTING,
    STARTED,
    DISPOSED,
  }

  private class ReadStateFailure(val failedState: ReqwsProjectState) :
    RuntimeException(failedState.lastError?.code ?: "MANIFEST_READ_FAILED") {
    override fun toString(): String =
      "ReadStateFailure(code=${failedState.lastError?.code ?: "MANIFEST_READ_FAILED"})"
  }

  companion object {
    private const val MANIFEST_RETRY_COUNT = 3
    private const val MANIFEST_RETRY_DELAY_MILLIS = 100L
    private const val MAX_PENDING_CANDIDATES = 8

    internal fun createForTest(
      project: Project,
      coroutineScope: CoroutineScope,
      runtimeOverrides: ReqwsProjectServiceRuntimeOverrides,
    ): ReqwsProjectService = ReqwsProjectService(
      project = project,
      coroutineScope = coroutineScope,
      runtimeOverrides = runtimeOverrides,
    )
  }
}

internal enum class VcsChangeMonitoringStart {
  STARTED_NOW,
  ALREADY_STARTED,
  UNAVAILABLE,
}

internal fun interface ReqwsVcsInspector {
  fun inspect(snapshot: ManifestSnapshot): VcsRootInspection
}

internal fun interface ReqwsVcsChangeRegistrar {
  fun addExternalChangeListener(listener: () -> Job?): AutoCloseable
}

internal data class ReqwsProjectServiceRuntimeOverrides(
  val trustGate: ReqwsTrustGate? = null,
  val candidateApplier: SyncCandidateApplier<ManifestSnapshot>? = null,
  val trustPollMillis: Long? = null,
  val trustPollWaiter: TrustPollWaiter? = null,
  val vcsChangeRegistrar: ReqwsVcsChangeRegistrar? = null,
  val vcsInspector: ReqwsVcsInspector? = null,
  val beforeVcsCallbackRefresh: (() -> Unit)? = null,
)
