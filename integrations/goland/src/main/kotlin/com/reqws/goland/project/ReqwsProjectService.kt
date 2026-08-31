package com.reqws.goland.project

import com.intellij.ide.trustedProjects.TrustedProjects
import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ModuleRootEvent
import com.intellij.openapi.roots.ModuleRootListener
import com.reqws.goland.manifest.ManifestErrorCode
import com.reqws.goland.manifest.ManifestReader
import com.reqws.goland.manifest.ManifestSnapshot
import com.reqws.goland.projectmodel.ReqwsProjectModelMutationGuard
import com.reqws.goland.sync.LatestWinsSyncCoordinator
import com.reqws.goland.sync.SyncCandidate
import com.reqws.goland.sync.SyncCandidateApplier
import com.reqws.goland.sync.SyncCandidateCommitter
import com.reqws.goland.sync.SyncCoordinatorEvent
import com.reqws.goland.sync.SyncCoordinatorObserver
import com.reqws.goland.sync.SyncFailureStage
import com.reqws.goland.sync.SyncTrigger
import com.reqws.goland.sync.mergeReconcileTrigger
import com.reqws.goland.vcs.ReqwsVcsConfigurationMonitor
import com.reqws.goland.vcs.ReqwsVcsDiagnosticsService
import com.reqws.goland.vcs.VcsRootInspection
import com.reqws.goland.watch.ManifestSyncRequest
import com.reqws.goland.watch.ManifestVfsWatcher
import java.nio.file.Path
import java.util.LinkedHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
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
  private val lifecycleCommitLock = Any()
  private val readRequests = SyncReadRequestTracker()
  private val statePublisher = TerminalStatePublisher(
    initialState = ReqwsProjectState.INACTIVE,
    isTerminal = { state -> state.lifecycle == ReqwsLifecycleState.DISPOSED },
    isStable = { state ->
      state.lifecycle != ReqwsLifecycleState.READING &&
        state.lifecycle != ReqwsLifecycleState.SYNCHRONIZING &&
        state.lifecycle != ReqwsLifecycleState.DISPOSED
    },
  )
  private val watcherRef = AtomicReference<Disposable?>()
  private val manifestWatcherFactory = runtimeOverrides?.manifestWatcherFactory
    ?: ReqwsManifestWatcherFactory { watchedProject, manifestPath, watchedScope, request ->
      ManifestVfsWatcher(
        project = watchedProject,
        manifestPath = manifestPath,
        coroutineScope = watchedScope,
        syncRequest = request,
      )
    }
  private val initialCancellationRetryRef = AtomicReference<Job?>()
  private val applyingState = AtomicReference<ApplyingState?>()
  private val candidateLock = Any()
  private val candidateStates = LinkedHashMap<String, CandidateState>()
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
        projectionApplier.apply(candidate.value, candidate.trigger)
      },
    committer = SyncCandidateCommitter { candidate ->
      runtimeOverrides?.beforeCandidateCommit?.invoke()
      synchronized(lifecycleCommitLock) {
        if (disposed.get() || project.isDisposed) {
          throw CancellationException("ReqWS project service was disposed before digest commit")
        }
        persistence.markApplied(candidate.digestSha256)
      }
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
  private val initialCancellationRetryWaiter =
    runtimeOverrides?.initialCancellationRetryWaiter
      ?: InitialCancellationRetryWaiter { delay(it) }
  private val projectModelChangeLifecycleLock = Any()
  private var projectModelChangeRegistration: AutoCloseable? = null
  private var projectModelChangeDebounceJob: Job? = null
  private var projectModelChangePendingIntent: ProjectModelRefreshIntent? = null
  private var projectModelChangeNextEventEpoch = 0L
  private var projectModelChangeRefreshStarted = false
  private val projectModelMutationGuard = project.service<ReqwsProjectModelMutationGuard>()
  private val projectModelChangeRegistrar = runtimeOverrides?.projectModelChangeRegistrar
    ?: ReqwsProjectModelChangeRegistrar { listener ->
      val connection = project.messageBus.connect()
      connection.subscribe(
        ModuleRootListener.TOPIC,
        object : ModuleRootListener {
          override fun rootsChanged(event: ModuleRootEvent) {
            listener(
              if (event.isCausedByWorkspaceModelChangesOnly) {
                ReqwsProjectModelChangeKind.WORKSPACE_MODEL_ONLY
              } else {
                ReqwsProjectModelChangeKind.ORDINARY
              },
            )
          }
        },
      )
      AutoCloseable(connection::disconnect)
    }
  private val projectModelChangeDebounceWaiter =
    runtimeOverrides?.projectModelChangeDebounceWaiter
      ?: ReqwsProjectModelChangeDebounceWaiter { delay(it) }

  init {
    registerProjectModelChangeMonitoring()
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

  private fun registerProjectModelChangeMonitoring() {
    val registration = projectModelChangeRegistrar.addProjectModelChangeListener(
      ::handleProjectModelChange,
    )
    val closeImmediately = synchronized(projectModelChangeLifecycleLock) {
      if (disposed.get() || project.isDisposed) {
        true
      } else {
        check(projectModelChangeRegistration == null) {
          "ReqWS project-model change monitoring was registered more than once"
        }
        projectModelChangeRegistration = registration
        false
      }
    }
    if (closeImmediately) registration.close()
  }

  private fun handleProjectModelChange(kind: ReqwsProjectModelChangeKind) {
    if (!shouldScheduleProjectModelChangeRefresh()) return
    val scheduled = synchronized(projectModelChangeLifecycleLock) {
      if (!shouldScheduleProjectModelChangeRefresh()) return@synchronized null
      runtimeOverrides?.beforeProjectModelIntentCapture?.invoke()
      val observedDigest = statePublisher.state.snapshot?.digestSha256
        ?: return@synchronized null
      projectModelChangeNextEventEpoch += 1
      val incomingIntent = when (kind) {
        ReqwsProjectModelChangeKind.WORKSPACE_MODEL_ONLY -> ProjectModelRefreshIntent(
          trigger = SyncTrigger.PROJECT_MODEL_CHANGE,
          originDigest = null,
          eventEpoch = projectModelChangeNextEventEpoch,
        )
        ReqwsProjectModelChangeKind.ORDINARY -> ProjectModelRefreshIntent(
          trigger = SyncTrigger.PROJECT_MODEL_FOLLOW_UP,
          originDigest = observedDigest,
          eventEpoch = projectModelChangeNextEventEpoch,
        )
      }
      val currentJob = projectModelChangeDebounceJob
      val nextIntent = if (currentJob != null && !projectModelChangeRefreshStarted) {
        mergeProjectModelRefreshIntent(projectModelChangePendingIntent, incomingIntent)
      } else {
        incomingIntent
      }
      currentJob?.cancel()
      projectModelChangePendingIntent = nextIntent
      projectModelChangeRefreshStarted = false
      coroutineScope.launch(Dispatchers.IO, start = CoroutineStart.LAZY) {
        val launchedJob = currentCoroutineContext()[Job]
        try {
          projectModelChangeDebounceWaiter.wait(PROJECT_MODEL_CHANGE_DEBOUNCE_MILLIS)
          val intent = synchronized(projectModelChangeLifecycleLock) {
            if (projectModelChangeDebounceJob !== launchedJob) {
              null
            } else {
              projectModelChangeRefreshStarted = true
              projectModelChangePendingIntent
            }
          } ?: return@launch
          if (
            !disposed.get() &&
            !project.isDisposed &&
            statePublisher.state.snapshot != null
          ) {
            requestRefresh(
              trigger = intent.trigger,
              projectModelOriginDigest = intent.originDigest,
              projectModelEventEpoch = intent.eventEpoch.takeIf {
                intent.trigger == SyncTrigger.PROJECT_MODEL_FOLLOW_UP
              },
            )?.join()
          }
        } finally {
          synchronized(projectModelChangeLifecycleLock) {
            if (projectModelChangeDebounceJob === launchedJob) {
              projectModelChangeDebounceJob = null
              projectModelChangePendingIntent = null
              projectModelChangeRefreshStarted = false
            }
          }
        }
      }.also { projectModelChangeDebounceJob = it }
    } ?: return
    scheduled.start()
  }

  private fun shouldScheduleProjectModelChangeRefresh(): Boolean =
    !disposed.get() &&
      !project.isDisposed &&
      statePublisher.state.snapshot != null &&
      !projectModelMutationGuard.isActive

  private fun mergeProjectModelRefreshIntent(
    current: ProjectModelRefreshIntent?,
    incoming: ProjectModelRefreshIntent,
  ): ProjectModelRefreshIntent {
    val mergedTrigger = mergeReconcileTrigger(current?.trigger, incoming.trigger)
      ?: incoming.trigger
    return ProjectModelRefreshIntent(
      trigger = mergedTrigger,
      originDigest = if (mergedTrigger == SyncTrigger.PROJECT_MODEL_FOLLOW_UP) {
        incoming.originDigest
      } else {
        null
      },
      eventEpoch = incoming.eventEpoch,
    )
  }

  private fun requestRefresh(
    trigger: SyncTrigger,
    requiredVcsRegistrationVersion: Long? = null,
    cancellationRecovery: InitialCancellationRecovery? = null,
    projectModelOriginDigest: String? = null,
    projectModelEventEpoch: Long? = null,
  ): Job? {
    if (disposed.get() || project.isDisposed) return null
    val request = when {
      cancellationRecovery != null -> {
        readRequests.beginCancellationRecoveryIf(cancellationRecovery.predecessor) {
          val current = statePublisher.snapshot()
          current.version == cancellationRecovery.expectedStateVersion &&
            current.state.lifecycle == ReqwsLifecycleState.INACTIVE &&
            current.state.snapshot == null
        } ?: return null
      }
      requiredVcsRegistrationVersion == null -> readRequests.begin(
        trigger = trigger,
        projectModelOriginDigest = projectModelOriginDigest,
        projectModelEventEpoch = projectModelEventEpoch,
      )
      else -> readRequests.beginIf(
        trigger = trigger,
        projectModelOriginDigest = projectModelOriginDigest,
        projectModelEventEpoch = projectModelEventEpoch,
      ) {
        synchronized(vcsChangeLifecycleLock) {
          vcsChangeMonitoringState == VcsChangeMonitoringState.STARTED &&
            vcsChangeRegistrationVersion == requiredVcsRegistrationVersion
        }
      } ?: return null
    }
    // A rejected conditional VCS callback must not consume the only startup recovery. Cancel the
    // pending timer only after this normal request has actually become the latest generation.
    if (cancellationRecovery == null) cancelPendingInitialCancellationRetry()
    var job: Job? = null
    val readingPublicationRef = AtomicReference<StatePublication<ReqwsProjectState>?>()
    val cancellationRecoveryRef = AtomicReference<InitialCancellationRecovery?>()
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
        val readingPublication = requireNotNull(readingPublicationRef.get()) {
          "ReqWS refresh started without a READING publication"
        }
        val previous = readingPublication.before.state.let { stateBeforeReading ->
          if (stateBeforeReading.lifecycle == ReqwsLifecycleState.READING) {
            readingPublication.before.stableState
          } else {
            stateBeforeReading
          }
        }
        try {
          projectRoot
            ?.let(ReqwsProjectDetector::canonicalProjectRoot)
            ?.let(::ensureWatcher)
          val loaded = loadWithRetry(projectRoot, previous)
          currentCoroutineContext().ensureActive()
          if (disposed.get()) return@launch
          acceptLoadedState(loaded, request, observedVcsRegistrationVersion)
        } catch (failure: ProcessCanceledException) {
          cancellationRecoveryRef.set(
            restoreStableStateAfterCancellationIfLatest(request, readingPublication, failure),
          )
          throw failure
        } catch (failure: CancellationException) {
          cancellationRecoveryRef.set(
            restoreStableStateAfterCancellationIfLatest(request, readingPublication, failure),
          )
          throw failure
        } catch (failure: Exception) {
          publishUnexpectedRefreshFailureIfLatest(request, previous)
          throw failure
        }
      }
      job = launched
      // The handler is installed before READING is published, so every exit after generation
      // creation—including cancellation before the coroutine body starts—releases an unaccepted
      // listener owned by the latest request. Accepted lifecycle registrations remain open.
      launched.invokeOnCompletion {
        cleanup()
        cancellationRecoveryRef.getAndSet(null)?.let(::scheduleInitialCancellationRetry)
      }
      runtimeOverrides?.beforeReadingPublication?.invoke()
      var readingPublication: StatePublication<ReqwsProjectState>? = null
      val readingPrepared = readRequests.runIfLatest(request) {
        readingPublication = if (cancellationRecovery == null) {
          statePublisher.prepareUpdate { current ->
            current.state.copy(
              lifecycle = ReqwsLifecycleState.READING,
              lastError = null,
            )
          }
        } else {
          val current = statePublisher.snapshot()
          if (current.version != cancellationRecovery.expectedStateVersion) {
            null
          } else {
            statePublisher.prepareCompareAndPublish(
              expectedVersion = cancellationRecovery.expectedStateVersion,
              next = current.state.copy(
                lifecycle = ReqwsLifecycleState.READING,
                lastError = null,
              ),
            )
          }
        }
      }
      val publication = readingPublication
      if (!readingPrepared || publication == null) {
        launched.cancel(CancellationException("ReqWS refresh was superseded before start"))
        return launched
      }
      readingPublicationRef.set(publication)
      val previous = publication.before.state.let { stateBeforeReading ->
        if (stateBeforeReading.lifecycle == ReqwsLifecycleState.READING) {
          publication.before.stableState
        } else {
          stateBeforeReading
        }
      }
      try {
        publication.deliver()
      } catch (failure: ProcessCanceledException) {
        cancellationRecoveryRef.set(
          restoreStableStateAfterCancellationIfLatest(request, publication, failure),
        )
        throw failure
      } catch (failure: CancellationException) {
        cancellationRecoveryRef.set(
          restoreStableStateAfterCancellationIfLatest(request, publication, failure),
        )
        throw failure
      } catch (failure: Exception) {
        publishUnexpectedRefreshFailureIfLatest(request, previous)
        throw failure
      }
      launched.start()
      launched
    } catch (failure: Throwable) {
      job?.cancel()
      cleanup()
      throw failure
    }
  }

  private fun restoreStableStateAfterCancellationIfLatest(
    request: SyncReadRequest,
    readingPublication: StatePublication<ReqwsProjectState>,
    cancellation: Throwable,
  ): InitialCancellationRecovery? {
    if (disposed.get() || project.isDisposed) return null
    var rollback: StatePublication<ReqwsProjectState>? = null
    try {
      runtimeOverrides?.beforeCancellationRollback?.invoke()
      readRequests.runIfLatest(request) {
        if (
          !disposed.get() &&
          !project.isDisposed
        ) {
          rollback = statePublisher.prepareCompareAndPublish(
            expectedVersion = readingPublication.after.version,
            next = readingPublication.after.stableState,
          )
        }
      }
      rollback?.deliver()
    } catch (restoreFailure: Throwable) {
      // State publication listeners must not replace a platform/coroutine termination signal.
      if (restoreFailure !== cancellation) cancellation.addSuppressed(restoreFailure)
    }
    return rollback?.toInitialCancellationRecovery(request)
  }

  /**
   * A first read/apply has no visible stable Tool Window state to fall back to. Give that exact
   * latest cancellation one delayed automatic successor in the lifecycle-owned scope. The
   * predecessor generation and rollback state version are rechecked before the retry can publish
   * READING, so a newer read/apply/dispose always wins and a second cancellation cannot loop.
   */
  private fun scheduleInitialCancellationRetry(recovery: InitialCancellationRecovery) {
    if (
      recovery.predecessor.cancellationRecoveryAttempt >=
      MAX_INITIAL_CANCELLATION_RETRY_ATTEMPTS ||
      disposed.get() ||
      project.isDisposed ||
      !coroutineScope.isActive
    ) {
      return
    }
    val retry = coroutineScope.launch(
      context = Dispatchers.IO,
      start = CoroutineStart.LAZY,
    ) {
      initialCancellationRetryWaiter.wait(INITIAL_CANCELLATION_RETRY_DELAY_MILLIS)
      currentCoroutineContext().ensureActive()
      if (
        disposed.get() ||
        project.isDisposed ||
        ReqwsProjectDetector.detect(project) == null
      ) {
        return@launch
      }
      requestRefresh(
        trigger = SyncTrigger.AUTOMATIC,
        cancellationRecovery = recovery,
      )
    }
    val previous = initialCancellationRetryRef.getAndSet(retry)
    previous?.cancel(CancellationException("ReqWS initial cancellation retry was replaced"))
    retry.invokeOnCompletion { initialCancellationRetryRef.compareAndSet(retry, null) }
    if (
      disposed.get() ||
      project.isDisposed ||
      !coroutineScope.isActive ||
      initialCancellationRetryRef.get() !== retry
    ) {
      initialCancellationRetryRef.compareAndSet(retry, null)
      retry.cancel(CancellationException("ReqWS initial cancellation retry is no longer active"))
      return
    }
    retry.start()
  }

  private fun cancelPendingInitialCancellationRetry() {
    initialCancellationRetryRef.getAndSet(null)?.cancel(
      CancellationException("ReqWS initial cancellation retry was superseded"),
    )
  }

  private fun StatePublication<ReqwsProjectState>.toInitialCancellationRecovery(
    predecessor: SyncReadRequest,
  ): InitialCancellationRecovery? = after.state.takeIf { state ->
    state.lifecycle == ReqwsLifecycleState.INACTIVE && state.snapshot == null
  }?.let {
    InitialCancellationRecovery(
      predecessor = predecessor,
      expectedStateVersion = after.version,
    )
  }

  private fun publishUnexpectedRefreshFailureIfLatest(
    request: SyncReadRequest,
    previous: ReqwsProjectState,
  ) {
    readRequests.runIfLatest(request) { trigger ->
      trustMonitor.cancelPending()
      val failedState = previous.copy(
        lifecycle = ReqwsLifecycleState.ERROR,
        lastError = ReqwsProjectError(
          code = ReqwsStableErrorCode.REFRESH_FAILED,
          digestSha256 = previous.snapshot?.digestSha256,
        ),
      )
      if (
        !coordinator.offerReadFailure(
          cause = ReadStateFailure(failedState),
          trigger = trigger,
          digestSha256 = failedState.lastError?.digestSha256,
        )
      ) {
        publish(failedState)
      }
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
        runtimeOverrides?.beforeCandidateOffer?.invoke()
        val accepted = readRequests.offerCandidateIfLatest(request) { trigger ->
          trustMonitor.cancelPending()
          rememberCandidate(snapshot.digestSha256, prepared.state, request)
          val projectionTrigger = projectionTriggerForDigest(
            request = request,
            trigger = trigger,
            digestSha256 = snapshot.digestSha256,
          )
          val offered = coordinator.offer(
            SyncCandidate(snapshot.digestSha256, snapshot),
            projectionTrigger,
          )
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

  private fun projectionTriggerForDigest(
    request: SyncReadRequest,
    trigger: SyncTrigger,
    digestSha256: String,
  ): SyncTrigger {
    if (trigger != SyncTrigger.PROJECT_MODEL_FOLLOW_UP) return trigger
    return if (
      request.projectModelOriginDigest == digestSha256 &&
      request.projectModelEventEpoch != null
    ) {
      SyncTrigger.PROJECT_MODEL_FOLLOW_UP
    } else {
      // A newer manifest supersedes the verify-only event lineage. Its different digest must be
      // allowed to publish the one ordinary roots notification required for fresh Go module roots.
      SyncTrigger.AUTOMATIC
    }
  }

  private fun onCoordinatorEvent(event: SyncCoordinatorEvent) {
    if (disposed.get()) return
    when (event) {
      is SyncCoordinatorEvent.Applying -> {
        val candidate = takeCandidate(event.digestSha256)
        val publication = statePublisher.prepareUpdate { current ->
          (candidate?.state ?: current.state).copy(
            lifecycle = ReqwsLifecycleState.SYNCHRONIZING,
            validatedProjectionDigest = null,
            lastError = null,
          )
        }
        if (publication != null) {
          applyingState.set(
            ApplyingState(
              digestSha256 = event.digestSha256,
              candidate = candidate,
              publication = publication,
            ),
          )
          publication.deliver()
        }
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
      is SyncCoordinatorEvent.Cancelled -> {
        val cancelled = takeApplyingState(event.digestSha256)
        val rollback = cancelled?.publication?.let { publication ->
          statePublisher.prepareCompareAndPublish(
            expectedVersion = publication.after.version,
            next = publication.after.stableState.copy(
              // An applier cancellation may arrive after an authoritative layer committed but
              // before every live gate completed. Keep the stable snapshot for recovery, but do
              // not keep advertising its previous live-projection proof.
              validatedProjectionDigest = null,
            ),
          )
        }
        val sourceRequest = cancelled?.candidate?.sourceRequest
        val recovery = if (sourceRequest != null && rollback != null) {
          rollback.toInitialCancellationRecovery(sourceRequest)
        } else {
          null
        }
        try {
          rollback?.deliver()
        } catch (failure: ProcessCanceledException) {
          // Observer cancellation retains its existing raw-termination boundary.
          throw failure
        } catch (failure: CancellationException) {
          throw failure
        } catch (failure: Exception) {
          // Ordinary observer failures remain isolated, but cannot suppress startup recovery.
          recovery?.let(::scheduleInitialCancellationRetry)
          throw failure
        }
        recovery?.let(::scheduleInitialCancellationRetry)
      }
      is SyncCoordinatorEvent.Failed -> handleCoordinatorFailure(event)
    }
  }

  private fun handleCoordinatorFailure(event: SyncCoordinatorEvent.Failed) {
    if (event.stage == SyncFailureStage.READ && event.cause is ReadStateFailure) {
      val failedState = event.cause.failedState
      statePublisher.prepareUpdate { current ->
        failedState.copy(
          // A read failure may have waited behind an apply that invalidated its old live proof.
          // Intersect with the proof current at publication time so queued state cannot resurrect
          // a digest that a later apply failure or cancellation already withdrew.
          validatedProjectionDigest = current.state.validatedProjectionDigest.takeIf { proof ->
            proof == failedState.snapshot?.digestSha256
          },
        )
      }?.deliver()
      return
    }

    val candidate = event.digestSha256?.let(::takeApplying)
      ?: event.digestSha256?.let(::takeCandidate)
    val projectionFailure = event.cause as? ReqwsProjectionApplyException
    if (projectionFailure?.stableCode == ReqwsStableErrorCode.SAFE_MODE_BLOCKED) {
      publish(
        (candidate?.state ?: state).copy(
          lifecycle = ReqwsLifecycleState.SAFE_MODE_BLOCKED,
          validatedProjectionDigest = null,
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
        validatedProjectionDigest = null,
        lastError = ReqwsProjectError(
          code = stableCode,
          field = projectionFailure?.field,
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
    if (watcherRef.get() != null || disposed.get() || project.isDisposed) return
    val watcher = manifestWatcherFactory.create(
      project = project,
      manifestPath = ReqwsProjectDetector.manifestPath(projectRoot),
      coroutineScope = coroutineScope,
      syncRequest = ManifestSyncRequest { requestRefresh(SyncTrigger.AUTOMATIC) },
    )
    if (!watcherRef.compareAndSet(null, watcher)) {
      watcher.dispose()
      return
    }
    // Disposal can finish while the factory is blocked. Withdraw the late publication when this
    // thread still owns it; otherwise dispose() already took responsibility for closing it.
    if (
      (disposed.get() || project.isDisposed) &&
      watcherRef.compareAndSet(watcher, null)
    ) {
      watcher.dispose()
    }
  }

  private fun rememberCandidate(
    digest: String,
    loaded: ReqwsProjectState,
    sourceRequest: SyncReadRequest,
  ) {
    synchronized(candidateLock) {
      candidateStates[digest] = CandidateState(
        digestSha256 = digest,
        state = loaded,
        sourceRequest = sourceRequest,
      )
      while (candidateStates.size > MAX_PENDING_CANDIDATES) {
        val eldest = candidateStates.entries.firstOrNull() ?: break
        candidateStates.remove(eldest.key)
      }
    }
  }

  private fun takeCandidate(digest: String): CandidateState? = synchronized(candidateLock) {
    candidateStates.remove(digest)
  }

  private fun takeApplyingState(digest: String): ApplyingState? {
    val active = applyingState.get()
    return if (active?.digestSha256 == digest && applyingState.compareAndSet(active, null)) {
      active
    } else {
      null
    }
  }

  private fun takeApplying(digest: String): CandidateState? =
    takeApplyingState(digest)?.candidate

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
    val ownsDisposal = synchronized(lifecycleCommitLock) {
      disposed.compareAndSet(false, true)
    }
    if (!ownsDisposal) return
    var firstFailure: Throwable? = null
    fun cleanup(action: () -> Unit) {
      try {
        action()
      } catch (failure: Throwable) {
        val currentFailure = firstFailure
        if (currentFailure == null) {
          firstFailure = failure
        } else if (currentFailure !== failure) {
          currentFailure.addSuppressed(failure)
        }
      }
    }

    cleanup { statePublisher.publish(ReqwsProjectState.DISPOSED) }
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
    cleanup { finishVcsChangeMonitoringRevocation(revocation) }
    cleanup { readRequests.invalidate() }
    cleanup { cancelPendingInitialCancellationRetry() }
    val projectModelChangeResources = synchronized(projectModelChangeLifecycleLock) {
      val resources = ProjectModelChangeResources(
        debounceJob = projectModelChangeDebounceJob,
        registration = projectModelChangeRegistration,
      )
      projectModelChangeDebounceJob = null
      projectModelChangePendingIntent = null
      projectModelChangeRefreshStarted = false
      projectModelChangeRegistration = null
      resources
    }
    cleanup { projectModelChangeResources.debounceJob?.cancel() }
    cleanup { projectModelChangeResources.registration?.close() }
    cleanup { trustMonitor.close() }
    cleanup { watcherRef.getAndSet(null)?.dispose() }
    cleanup { coordinator.close() }
    cleanup { synchronized(candidateLock) { candidateStates.clear() } }
    cleanup { applyingState.set(null) }
    firstFailure?.let { throw it }
  }

  private data class CandidateState(
    val digestSha256: String,
    val state: ReqwsProjectState,
    val sourceRequest: SyncReadRequest,
  )

  private data class ApplyingState(
    val digestSha256: String,
    val candidate: CandidateState?,
    val publication: StatePublication<ReqwsProjectState>,
  )

  private data class InitialCancellationRecovery(
    val predecessor: SyncReadRequest,
    val expectedStateVersion: Long,
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

  private data class ProjectModelChangeResources(
    val debounceJob: Job?,
    val registration: AutoCloseable?,
  )

  private data class ProjectModelRefreshIntent(
    val trigger: SyncTrigger,
    val originDigest: String?,
    val eventEpoch: Long,
  ) {
    init {
      require(
        (trigger == SyncTrigger.PROJECT_MODEL_FOLLOW_UP) == (originDigest != null),
      ) {
        "Only a verify-only project-model follow-up may carry an origin digest"
      }
    }
  }

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
    private const val MAX_INITIAL_CANCELLATION_RETRY_ATTEMPTS = 1
    private const val INITIAL_CANCELLATION_RETRY_DELAY_MILLIS = 250L
    private const val PROJECT_MODEL_CHANGE_DEBOUNCE_MILLIS = 250L

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

internal fun interface ReqwsProjectModelChangeRegistrar {
  fun addProjectModelChangeListener(
    listener: (ReqwsProjectModelChangeKind) -> Unit,
  ): AutoCloseable
}

internal enum class ReqwsProjectModelChangeKind {
  WORKSPACE_MODEL_ONLY,
  ORDINARY,
}

internal fun interface ReqwsProjectModelChangeDebounceWaiter {
  suspend fun wait(delayMillis: Long)
}

internal fun interface InitialCancellationRetryWaiter {
  suspend fun wait(delayMillis: Long)
}

internal fun interface ReqwsManifestWatcherFactory {
  fun create(
    project: Project,
    manifestPath: Path,
    coroutineScope: CoroutineScope,
    syncRequest: ManifestSyncRequest,
  ): Disposable
}

internal data class ReqwsProjectServiceRuntimeOverrides(
  val trustGate: ReqwsTrustGate? = null,
  val candidateApplier: SyncCandidateApplier<ManifestSnapshot>? = null,
  val trustPollMillis: Long? = null,
  val trustPollWaiter: TrustPollWaiter? = null,
  val vcsChangeRegistrar: ReqwsVcsChangeRegistrar? = null,
  val projectModelChangeRegistrar: ReqwsProjectModelChangeRegistrar? = null,
  val projectModelChangeDebounceWaiter: ReqwsProjectModelChangeDebounceWaiter? = null,
  val vcsInspector: ReqwsVcsInspector? = null,
  val beforeReadingPublication: (() -> Unit)? = null,
  val beforeCancellationRollback: (() -> Unit)? = null,
  val beforeVcsCallbackRefresh: (() -> Unit)? = null,
  val beforeProjectModelIntentCapture: (() -> Unit)? = null,
  val beforeCandidateOffer: (() -> Unit)? = null,
  val beforeCandidateCommit: (() -> Unit)? = null,
  val initialCancellationRetryWaiter: InitialCancellationRetryWaiter? = null,
  val manifestWatcherFactory: ReqwsManifestWatcherFactory? = null,
)
