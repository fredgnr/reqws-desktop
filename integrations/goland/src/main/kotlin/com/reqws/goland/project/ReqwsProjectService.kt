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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
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
      requestRefresh(SyncTrigger.TRUST_TRANSITION)
    },
    pollMillis = runtimeOverrides?.trustPollMillis
      ?: TrustTransitionMonitor.DEFAULT_POLL_MILLIS,
    waiter = runtimeOverrides?.trustPollWaiter
      ?: TrustPollWaiter { delay(it) },
  )
  private val vcsChangeLifecycleLock = Any()
  private var vcsChangeMonitoringState = VcsChangeMonitoringState.NOT_STARTED
  private var vcsChangeRegistrationVersion = 0L
  private var vcsChangeRegistration: AutoCloseable? = null
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
  internal fun startVcsChangeMonitoring(
    registrar: ReqwsVcsChangeRegistrar = vcsChangeRegistrar,
  ): VcsChangeMonitoringStart = synchronized(vcsChangeLifecycleLock) {
    if (disposed.get() || project.isDisposed) {
      vcsChangeMonitoringState = VcsChangeMonitoringState.DISPOSED
      val registration = vcsChangeRegistration
      vcsChangeRegistration = null
      closeVcsChangeRegistration(registration)
      return@synchronized VcsChangeMonitoringStart.UNAVAILABLE
    }
    when (vcsChangeMonitoringState) {
      VcsChangeMonitoringState.STARTED -> {
        return@synchronized VcsChangeMonitoringStart.ALREADY_STARTED
      }
      VcsChangeMonitoringState.DISPOSED -> {
        return@synchronized VcsChangeMonitoringStart.UNAVAILABLE
      }
      // Only a same-thread registrar re-entry can observe STARTING because other callers wait.
      VcsChangeMonitoringState.STARTING -> {
        return@synchronized VcsChangeMonitoringStart.UNAVAILABLE
      }
      VcsChangeMonitoringState.NOT_STARTED -> Unit
    }

    vcsChangeMonitoringState = VcsChangeMonitoringState.STARTING
    val registration = try {
      registrar.addExternalChangeListener(::handleExternalVcsConfigurationChange)
    } catch (failure: Throwable) {
      vcsChangeMonitoringState = if (disposed.get() || project.isDisposed) {
        VcsChangeMonitoringState.DISPOSED
      } else {
        VcsChangeMonitoringState.NOT_STARTED
      }
      throw failure
    }

    // Publish the handle before the terminal recheck. If dispose won while registration was in
    // progress, this branch owns closing it; otherwise a later dispose takes it under the same lock.
    vcsChangeRegistration = registration
    if (disposed.get() || project.isDisposed) {
      vcsChangeMonitoringState = VcsChangeMonitoringState.DISPOSED
      vcsChangeRegistration = null
      closeVcsChangeRegistration(registration)
      return@synchronized VcsChangeMonitoringStart.UNAVAILABLE
    }

    vcsChangeMonitoringState = VcsChangeMonitoringState.STARTED
    vcsChangeRegistrationVersion += 1
    VcsChangeMonitoringStart.STARTED_NOW
  }

  private fun handleExternalVcsConfigurationChange(): Job? {
    if (disposed.get() || project.isDisposed) return null
    val started = synchronized(vcsChangeLifecycleLock) {
      vcsChangeMonitoringState == VcsChangeMonitoringState.STARTED
    }
    // A registrar may invoke its callback synchronously. The mandatory post-registration
    // inspection covers every change before STARTED, without recursively starting a newer
    // manifest read while the first valid candidate is still being accepted.
    if (!started) return null
    // Once active, VCS changes retain the existing full automatic refresh semantics: a clean
    // model baseline NoOps after publishing fresh diagnostics, while an already-dirty baseline
    // can converge through the normal coordinator.
    return coroutineScope.launch(Dispatchers.IO) {
      requestRefresh(SyncTrigger.AUTOMATIC)?.join()
    }
  }

  private fun requestRefresh(trigger: SyncTrigger): Job? {
    if (disposed.get() || project.isDisposed) return null
    val projectRoot = ReqwsProjectDetector.projectRoot(project)
    val request = readRequests.begin(trigger)
    val observedVcsRegistrationVersion = currentVcsRegistrationVersion()
    publish(
      state.copy(
        lifecycle = ReqwsLifecycleState.READING,
        lastError = null,
      ),
    )
    return coroutineScope.launch(Dispatchers.IO) {
      projectRoot
        ?.let(ReqwsProjectDetector::canonicalProjectRoot)
        ?.let(::ensureWatcher)
      val previous = state
      val loaded = loadWithRetry(projectRoot, previous)
      if (disposed.get()) return@launch
      acceptLoadedState(loaded, request, observedVcsRegistrationVersion)
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

  private fun acceptLoadedState(
    loaded: ReqwsProjectState,
    request: SyncReadRequest,
    observedVcsRegistrationVersion: Long,
  ) {
    when (loaded.lifecycle) {
      ReqwsLifecycleState.INACTIVE -> {
        readRequests.runIfLatest(request) {
          trustMonitor.cancelPending()
          publish(loaded)
        }
      }
      ReqwsLifecycleState.SAFE_MODE_BLOCKED -> {
        if (!readRequests.isLatest(request)) return
        val monitored = prepareValidState(loaded, observedVcsRegistrationVersion) ?: return
        readRequests.runIfLatest(request) {
          publish(monitored)
          trustMonitor.awaitTrusted()
        }
      }
      ReqwsLifecycleState.ERROR -> {
        readRequests.runIfLatest(request) { trigger ->
          trustMonitor.cancelPending()
          coordinator.offerReadFailure(
            cause = ReadStateFailure(loaded),
            trigger = trigger,
            digestSha256 = loaded.lastError?.digestSha256,
          )
        }
      }
      ReqwsLifecycleState.SYNCHRONIZED,
      ReqwsLifecycleState.DEGRADED,
      -> {
        val snapshot = requireNotNull(loaded.snapshot)
        if (!readRequests.isLatest(request)) return
        val monitored = prepareValidState(loaded, observedVcsRegistrationVersion) ?: return
        readRequests.offerCandidateIfLatest(request) { trigger ->
          trustMonitor.cancelPending()
          rememberCandidate(snapshot.digestSha256, monitored)
          coordinator.offer(SyncCandidate(snapshot.digestSha256, snapshot), trigger)
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
   * caller performs both latest-read gates; registration and inspection stay outside that lock so
   * an EDT Sync Now request never waits for VCS IO.
   */
  private fun prepareValidState(
    loaded: ReqwsProjectState,
    observedVcsRegistrationVersion: Long,
  ): ReqwsProjectState? {
    val snapshot = requireNotNull(loaded.snapshot) {
      "VCS monitoring requires a valid manifest snapshot"
    }
    val start = startVcsChangeMonitoring()
    if (start == VcsChangeMonitoringStart.UNAVAILABLE) return null
    val registeredAfterReadStarted =
      observedVcsRegistrationVersion != currentVcsRegistrationVersion()
    return if (start == VcsChangeMonitoringStart.STARTED_NOW || registeredAfterReadStarted) {
      loaded.copy(
        vcsInspection = vcsInspector.inspect(snapshot),
      )
    } else {
      loaded
    }
  }

  private fun currentVcsRegistrationVersion(): Long = synchronized(vcsChangeLifecycleLock) {
    vcsChangeRegistrationVersion
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
      synchronized(vcsChangeLifecycleLock) {
        vcsChangeMonitoringState = VcsChangeMonitoringState.DISPOSED
        val registration = vcsChangeRegistration
        vcsChangeRegistration = null
        closeVcsChangeRegistration(registration)
      }
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

  private enum class VcsChangeMonitoringState {
    NOT_STARTED,
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
)
