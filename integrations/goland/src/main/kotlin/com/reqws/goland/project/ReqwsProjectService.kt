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
class ReqwsProjectService(
  private val project: Project,
  private val coroutineScope: CoroutineScope,
) : Disposable {
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
  private val trustGate = ReqwsTrustGate { TrustedProjects.isProjectTrusted(project) }
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
    applier = SyncCandidateApplier<ManifestSnapshot> { candidate ->
      projectionApplier.apply(candidate.value)
    },
    observer = SyncCoordinatorObserver(::onCoordinatorEvent),
  )
  private val trustMonitor = TrustTransitionMonitor(
    scope = coroutineScope,
    probe = TrustStateProbe(trustGate::isTrusted),
    action = TrustedTransitionAction {
      requestRefresh(SyncTrigger.AUTOMATIC)
    },
  )
  private val vcsChangeLifecycleLock = Any()
  private var vcsChangeMonitoringState = VcsChangeMonitoringState.NOT_STARTED
  private var vcsChangeRegistration: AutoCloseable? = null
  private val vcsChangeRegistrar = ReqwsVcsChangeRegistrar { listener ->
    project.service<ReqwsVcsConfigurationMonitor>()
      .addExternalChangeListener { listener() }
  }

  val state: ReqwsProjectState
    get() = statePublisher.state

  /** Sync Now bypasses VFS debounce but enters the same serialized coordinator. */
  fun refresh(): Job? = refreshAfterStart(SyncTrigger.MANUAL)

  /** Startup, VFS, trust, and Tool Window lifecycle refreshes retain automatic no-op semantics. */
  internal fun refreshAutomatically(): Job? = refreshAfterStart(SyncTrigger.AUTOMATIC)

  private fun refreshAfterStart(trigger: SyncTrigger): Job? {
    if (!startVcsChangeMonitoring()) return null
    return requestRefresh(trigger)
  }

  /**
   * Starts callback registration only after the service constructor has returned to its caller.
   * A registrar is allowed to invoke the callback synchronously during registration.
   */
  internal fun startVcsChangeMonitoring(
    registrar: ReqwsVcsChangeRegistrar = vcsChangeRegistrar,
  ): Boolean = synchronized(vcsChangeLifecycleLock) {
    if (disposed.get() || project.isDisposed) {
      vcsChangeMonitoringState = VcsChangeMonitoringState.DISPOSED
      val registration = vcsChangeRegistration
      vcsChangeRegistration = null
      closeVcsChangeRegistration(registration)
      return@synchronized false
    }
    when (vcsChangeMonitoringState) {
      VcsChangeMonitoringState.STARTED -> return@synchronized true
      VcsChangeMonitoringState.DISPOSED -> return@synchronized false
      // Only a same-thread registrar re-entry can observe STARTING because other callers wait for
      // this monitor. The registered callback bypasses start and therefore never takes this path.
      VcsChangeMonitoringState.STARTING -> return@synchronized false
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
      return@synchronized false
    }

    vcsChangeMonitoringState = VcsChangeMonitoringState.STARTED
    true
  }

  private fun handleExternalVcsConfigurationChange(): Job? {
    if (disposed.get() || project.isDisposed) return null
    // A platform writer may publish after a previously successful same-digest apply. Carry a
    // monotonic dirty epoch into the automatic candidate so an in-flight older apply cannot make
    // this recovery request look clean again.
    coordinator.invalidateAppliedDigest()
    // Registration-time callbacks must not re-enter the STARTING state. Once the platform can
    // invoke this listener, the current registration attempt already owns the startup boundary.
    return requestRefresh(SyncTrigger.AUTOMATIC)
  }

  private fun requestRefresh(trigger: SyncTrigger): Job? {
    if (disposed.get() || project.isDisposed) return null
    val projectRoot = ReqwsProjectDetector.projectRoot(project)
    val request = readRequests.begin(trigger)
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
      acceptLoadedState(loaded, request)
    }
  }

  private suspend fun loadWithRetry(
    projectRoot: Path?,
    previous: ReqwsProjectState,
  ): ReqwsProjectState {
    var loaded = loader.load(projectRoot, previous)
    repeat(MANIFEST_RETRY_COUNT - 1) {
      if (!loaded.isRetryableManifestGap()) return loaded.withPersistedDigest()
      delay(MANIFEST_RETRY_DELAY_MILLIS)
      loaded = loader.load(projectRoot, previous)
    }
    return loaded.withPersistedDigest()
  }

  private fun acceptLoadedState(loaded: ReqwsProjectState, request: SyncReadRequest) {
    when (loaded.lifecycle) {
      ReqwsLifecycleState.INACTIVE -> {
        readRequests.runIfLatest(request) {
          trustMonitor.cancelPending()
          publish(loaded)
        }
      }
      ReqwsLifecycleState.SAFE_MODE_BLOCKED -> {
        readRequests.runIfLatest(request) {
          publish(loaded)
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
        readRequests.offerCandidateIfLatest(request) { trigger ->
          trustMonitor.cancelPending()
          rememberCandidate(snapshot.digestSha256, loaded)
          coordinator.offer(
            candidate = SyncCandidate(snapshot.digestSha256, snapshot),
            trigger = trigger,
          )
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
          (candidate?.state ?: state).copy(
            lifecycle = ReqwsLifecycleState.SYNCHRONIZED,
            lastAppliedDigest = persistence.lastAppliedDigest() ?: event.digestSha256,
            lastError = null,
          ),
        )
      }
      is SyncCoordinatorEvent.NoOp -> {
        val candidate = takeCandidate(event.digestSha256)
        val loaded = candidate?.state ?: state
        publish(
          loaded.copy(
            lifecycle = if (loaded.snapshot?.missingRepositoryCount == 0) {
              ReqwsLifecycleState.SYNCHRONIZED
            } else {
              ReqwsLifecycleState.DEGRADED
            },
            lastAppliedDigest = persistence.lastAppliedDigest() ?: event.digestSha256,
            lastError = null,
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
  }
}

internal fun interface ReqwsVcsChangeRegistrar {
  fun addExternalChangeListener(listener: () -> Job?): AutoCloseable
}
