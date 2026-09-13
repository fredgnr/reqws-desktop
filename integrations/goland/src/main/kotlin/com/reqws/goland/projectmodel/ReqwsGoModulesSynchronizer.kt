package com.reqws.goland.projectmodel

import com.goide.vgo.project.VgoModulesRegistry
import com.intellij.ide.trustedProjects.TrustedProjects
import com.intellij.openapi.application.edtWriteAction
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.module.ModuleManager
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.RootsChangeRescanningInfo
import com.intellij.openapi.roots.ex.ProjectRootManagerEx
import com.reqws.goland.diagnostics.ReqwsSyncTrace
import com.reqws.goland.diagnostics.SyncTraceEvent
import com.reqws.goland.diagnostics.SyncTraceField
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

internal fun interface ReqwsProjectRootsChangeNotifier {
  suspend fun notifyRootsChanged(): Boolean
}

internal fun interface ReqwsGoModulesRegistryView {
  fun moduleRoots(moduleName: String): Collection<Path>
}

internal fun interface ReqwsGoModulesSyncWaiter {
  suspend fun await(delayMillis: Long)
}

internal fun interface ReqwsGoModulesProjection {
  suspend fun synchronize(
    moduleName: String,
    activeRepositoryPaths: Collection<Path>,
    excludedPaths: Collection<Path>,
    allowRootsChangeNotification: Boolean,
  )
}

internal suspend fun ReqwsGoModulesProjection.synchronize(
  moduleName: String,
  activeRepositoryPaths: Collection<Path>,
  excludedPaths: Collection<Path>,
) = synchronize(
  moduleName = moduleName,
  activeRepositoryPaths = activeRepositoryPaths,
  excludedPaths = excludedPaths,
  allowRootsChangeNotification = true,
)

/**
 * Closes the remaining gap between the public project file index and GoLand's Go Modules
 * registry. GoLand deliberately ignores roots events caused only by Workspace Model changes, so
 * an exclude removal can make a repository searchable while package run configurations still use
 * a stale module registry. ReqWS publishes one ordinary public roots event but never invokes a Go
 * tracker, downloader, command, or process API; GoLand owns any native reaction to that event.
 */
internal class ReqwsGoModulesSynchronizer(
  private val registryView: ReqwsGoModulesRegistryView,
  private val rootsChangeNotifier: ReqwsProjectRootsChangeNotifier,
  private val isProjectDisposed: () -> Boolean,
  private val isTrusted: () -> Boolean,
  private val maxWaitCycles: Int = DEFAULT_MAX_WAIT_CYCLES,
  private val retryDelayMillis: Long = DEFAULT_RETRY_DELAY_MILLIS,
  private val waiter: ReqwsGoModulesSyncWaiter = ReqwsGoModulesSyncWaiter { delay(it) },
  private val trace: ReqwsSyncTrace = ReqwsSyncTrace.NONE,
) : ReqwsGoModulesProjection {
  constructor(
    project: Project,
    isProjectDisposed: () -> Boolean = project::isDisposed,
    isTrusted: () -> Boolean = { TrustedProjects.isProjectTrusted(project) },
    maxWaitCycles: Int = DEFAULT_MAX_WAIT_CYCLES,
    retryDelayMillis: Long = DEFAULT_RETRY_DELAY_MILLIS,
    waiter: ReqwsGoModulesSyncWaiter = ReqwsGoModulesSyncWaiter { delay(it) },
    trace: ReqwsSyncTrace = project.service(),
  ) : this(
    registryView = PlatformReqwsGoModulesRegistryView(project),
    rootsChangeNotifier = PlatformReqwsProjectRootsChangeNotifier(
      project = project,
      canNotify = { !isProjectDisposed() && isTrusted() },
    ),
    isProjectDisposed = isProjectDisposed,
    isTrusted = isTrusted,
    maxWaitCycles = maxWaitCycles,
    retryDelayMillis = retryDelayMillis,
    waiter = waiter,
    trace = trace,
  )

  init {
    require(maxWaitCycles > 0) { "Go Modules synchronization requires a positive wait bound." }
    require(retryDelayMillis >= 0) { "Go Modules synchronization delay cannot be negative." }
  }

  override suspend fun synchronize(
    moduleName: String,
    activeRepositoryPaths: Collection<Path>,
    excludedPaths: Collection<Path>,
    allowRootsChangeNotification: Boolean,
  ) {
    val invocation = if (trace.enabled) RegistryInvocationTrace(trace, allowRootsChangeNotification) else null
    try {
      require(moduleName.isNotBlank()) { "A Go Modules synchronization target is required." }
      ensureProjectActive()

      val expectedActiveRoots = activeRepositoryPaths
        .asSequence()
        .filter(::hasRegularTopLevelGoMod)
        .map(::canonicalOrAbsolutePath)
        .toSet()
      val canonicalExcludedPaths = excludedPaths
        .asSequence()
        .map(::canonicalOrAbsolutePath)
        .toSet()
      var rootsNotified = false
      repeat(maxWaitCycles) { waitCycle ->
        ensureProjectActive()
        var mismatch = projectionMismatch(
          moduleName = moduleName,
          expectedActiveRoots = expectedActiveRoots,
          excludedPaths = canonicalExcludedPaths,
          invocation = invocation,
        )
        if (mismatch == null) {
          logConverged(waitCycle, rootsNotified)
          invocation?.outcome = RegistryTraceOutcome.SUCCESS
          return
        }

        if (allowRootsChangeNotification && !rootsNotified) {
          invocation?.notificationStarted()
          rootsNotified = rootsChangeNotifier.notifyRootsChanged()
          invocation?.notificationCompleted(rootsNotified)
          if (rootsNotified) {
            LOG.info("ReqWS requested one ordinary roots change for Go Modules registry convergence.")
          }
          ensureProjectActive()
          mismatch = projectionMismatch(
            moduleName = moduleName,
            expectedActiveRoots = expectedActiveRoots,
            excludedPaths = canonicalExcludedPaths,
            invocation = invocation,
          )
          if (mismatch == null) {
            logConverged(waitCycle, rootsNotified)
            invocation?.outcome = RegistryTraceOutcome.SUCCESS
            return
          }
        }

        invocation?.waitStarted()
        waiter.await(retryDelayMillis)
      }

      ensureProjectActive()
      val mismatch = projectionMismatch(
        moduleName = moduleName,
        expectedActiveRoots = expectedActiveRoots,
        excludedPaths = canonicalExcludedPaths,
        invocation = invocation,
      )
      if (mismatch == null) {
        logConverged(maxWaitCycles, rootsNotified)
        invocation?.outcome = RegistryTraceOutcome.SUCCESS
        return
      }
      LOG.warn(
        "ReqWS Go Modules registry did not converge within $maxWaitCycles wait cycles; " +
          "rootsChangeRequested=$rootsNotified.",
      )
      invocation?.outcome = RegistryTraceOutcome.NOT_CONVERGED
      throw ProjectModelApplyException(
        ProjectModelErrorCode.GO_MODULES_REGISTRY_NOT_CONVERGED,
        "The ReqWS project content converged, but the Go Modules registry did not. $mismatch",
      )
    } catch (cancelled: ProcessCanceledException) {
      invocation?.outcome = RegistryTraceOutcome.CANCELLED
      throw cancelled
    } catch (cancelled: CancellationException) {
      invocation?.outcome = RegistryTraceOutcome.CANCELLED
      throw cancelled
    } finally {
      invocation?.finish()
    }
  }

  private fun projectionMismatch(
    moduleName: String,
    expectedActiveRoots: Set<Path>,
    excludedPaths: Set<Path>,
    invocation: RegistryInvocationTrace?,
  ): String? {
    invocation?.readStarted()
    val registeredRoots = registryView.moduleRoots(moduleName)
      .asSequence()
      .map(::canonicalOrAbsolutePath)
      .toSet()
    val missingActiveRoot = expectedActiveRoots.firstOrNull { it !in registeredRoots }
    if (missingActiveRoot != null) {
      return "Active Go module is missing: ${pathLabel(missingActiveRoot)}."
    }
    val retainedExcludedRoot = registeredRoots.firstOrNull { registeredRoot ->
      excludedPaths.any { excludedPath ->
        registeredRoot == excludedPath || registeredRoot.startsWith(excludedPath)
      }
    }
    return retainedExcludedRoot?.let { path ->
      "Excluded Go module remains registered: ${pathLabel(path)}."
    }
  }

  private fun logConverged(waitCycles: Int, rootsChangeRequested: Boolean) {
    if (waitCycles == 0 && !rootsChangeRequested) return
    LOG.info(
      "ReqWS Go Modules registry converged; waitCycles=$waitCycles; " +
        "rootsChangeRequested=$rootsChangeRequested.",
    )
  }

  private fun ensureProjectActive() {
    if (isProjectDisposed()) {
      throw ProjectModelApplyException(
        ProjectModelErrorCode.PROJECT_DISPOSED,
        "Project was disposed before the ReqWS Go Modules registry converged.",
      )
    }
    if (!isTrusted()) {
      throw ProjectModelApplyException(
        ProjectModelErrorCode.UNTRUSTED_PROJECT,
        "ReqWS project roots notifications are disabled for untrusted projects.",
      )
    }
  }

  private fun hasRegularTopLevelGoMod(repositoryRoot: Path): Boolean =
    Files.isRegularFile(repositoryRoot.resolve(GO_MOD_FILE_NAME), LinkOption.NOFOLLOW_LINKS)

  private fun canonicalOrAbsolutePath(path: Path): Path = try {
    path.toRealPath()
  } catch (_: IOException) {
    path.toAbsolutePath().normalize()
  }

  private fun pathLabel(path: Path): String = path.fileName?.toString() ?: "<root>"

  companion object {
    const val DEFAULT_MAX_WAIT_CYCLES = 300
    const val DEFAULT_RETRY_DELAY_MILLIS = 100L
    private const val GO_MOD_FILE_NAME = "go.mod"
    private val LOG: Logger = Logger.getInstance(ReqwsGoModulesSynchronizer::class.java)
  }
}

private enum class RegistryTraceOutcome {
  SUCCESS,
  NOT_CONVERGED,
  FAILED,
  CANCELLED,
}

/** Allocated only when tracing is enabled; none of these values participate in synchronization. */
private class RegistryInvocationTrace(
  private val trace: ReqwsSyncTrace,
  private val allowNotification: Boolean,
) {
  private val spanId = trace.nextSpanId()
  private val startedAt = trace.nanoTime()
  private var reads = 0
  private var waits = 0
  private var notifications = 0
  private var notificationAttempts = 0
  private var notificationRejected = 0
  var outcome = RegistryTraceOutcome.FAILED

  init {
    trace.record(
      SyncTraceEvent.REGISTRY_START,
      SyncTraceField.SPAN_ID(spanId),
      SyncTraceField.ALLOW_NOTIFICATION(allowNotification),
    )
  }

  fun readStarted() {
    reads += 1
  }

  fun waitStarted() {
    waits += 1
  }

  fun notificationStarted() {
    notificationAttempts += 1
  }

  fun notificationCompleted(published: Boolean) {
    if (published) notifications += 1 else notificationRejected += 1
    trace.record(
      SyncTraceEvent.ROOTS_NOTIFICATION,
      SyncTraceField.SPAN_ID(spanId),
      SyncTraceField.ACCEPTED(published),
      SyncTraceField.NOTIFICATION_ATTEMPTS(notificationAttempts),
      SyncTraceField.NOTIFICATION_REJECTED(notificationRejected),
      SyncTraceField.NOTIFICATIONS(notifications),
    )
  }

  fun finish() {
    trace.record(
      SyncTraceEvent.REGISTRY_END,
      SyncTraceField.SPAN_ID(spanId),
      SyncTraceField.ALLOW_NOTIFICATION(allowNotification),
      SyncTraceField.READS(reads),
      SyncTraceField.WAITS(waits),
      SyncTraceField.NOTIFICATIONS(notifications),
      SyncTraceField.NOTIFICATION_ATTEMPTS(notificationAttempts),
      SyncTraceField.NOTIFICATION_REJECTED(notificationRejected),
      SyncTraceField.ELAPSED_NANOS(trace.elapsedNanos(startedAt)),
      SyncTraceField.OUTCOME(outcome),
    )
  }
}

internal class PlatformReqwsProjectRootsChangeNotifier(
  private val project: Project,
  private val canNotify: () -> Boolean = { !project.isDisposed },
) : ReqwsProjectRootsChangeNotifier {
  private val mutationGuard = project.service<ReqwsProjectModelMutationGuard>()

  override suspend fun notifyRootsChanged(): Boolean = edtWriteAction {
    mutationGuard.withMutation {
      if (project.isDisposed || !canNotify()) {
        false
      } else {
        ProjectRootManagerEx.getInstanceEx(project).makeRootsChange(
          {},
          RootsChangeRescanningInfo.NO_RESCAN_NEEDED,
        )
        true
      }
    }
  }
}

private class PlatformReqwsGoModulesRegistryView(
  private val project: Project,
) : ReqwsGoModulesRegistryView {
  override fun moduleRoots(moduleName: String): Collection<Path> {
    if (project.isDisposed) {
      throw ProjectModelApplyException(
        ProjectModelErrorCode.PROJECT_DISPOSED,
        "Project was disposed before the ReqWS Go Modules registry could be read.",
      )
    }
    val module = ModuleManager.getInstance(project).findModuleByName(moduleName)
      ?: throw ProjectModelApplyException(
        ProjectModelErrorCode.OWNERSHIP_CONFLICT,
        "The target module disappeared before the ReqWS Go Modules registry could be read.",
      )
    if (module.isDisposed) {
      throw ProjectModelApplyException(
        ProjectModelErrorCode.OWNERSHIP_CONFLICT,
        "The target module was disposed before the ReqWS Go Modules registry could be read.",
      )
    }
    return VgoModulesRegistry.getInstance(project)
      .getModules(module)
      .map { goModule -> Path.of(goModule.root.path) }
  }
}
