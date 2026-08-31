package com.reqws.goland.projectmodel

import com.goide.vgo.project.VgoModulesRegistry
import com.intellij.ide.trustedProjects.TrustedProjects
import com.intellij.openapi.application.edtWriteAction
import com.intellij.openapi.components.service
import com.intellij.openapi.module.ModuleManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.RootsChangeRescanningInfo
import com.intellij.openapi.roots.ex.ProjectRootManagerEx
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
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
) : ReqwsGoModulesProjection {
  constructor(
    project: Project,
    isProjectDisposed: () -> Boolean = project::isDisposed,
    isTrusted: () -> Boolean = { TrustedProjects.isProjectTrusted(project) },
    maxWaitCycles: Int = DEFAULT_MAX_WAIT_CYCLES,
    retryDelayMillis: Long = DEFAULT_RETRY_DELAY_MILLIS,
    waiter: ReqwsGoModulesSyncWaiter = ReqwsGoModulesSyncWaiter { delay(it) },
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
    repeat(maxWaitCycles) {
      ensureProjectActive()
      var mismatch = projectionMismatch(
        moduleName = moduleName,
        expectedActiveRoots = expectedActiveRoots,
        excludedPaths = canonicalExcludedPaths,
      )
      if (mismatch == null) return

      if (allowRootsChangeNotification && !rootsNotified) {
        rootsNotified = rootsChangeNotifier.notifyRootsChanged()
        ensureProjectActive()
        mismatch = projectionMismatch(
          moduleName = moduleName,
          expectedActiveRoots = expectedActiveRoots,
          excludedPaths = canonicalExcludedPaths,
        )
        if (mismatch == null) return
      }

      waiter.await(retryDelayMillis)
    }

    ensureProjectActive()
    val mismatch = projectionMismatch(
      moduleName = moduleName,
      expectedActiveRoots = expectedActiveRoots,
      excludedPaths = canonicalExcludedPaths,
    )
    if (mismatch == null) return
    throw ProjectModelApplyException(
      ProjectModelErrorCode.GO_MODULES_REGISTRY_NOT_CONVERGED,
      "The ReqWS project content converged, but the Go Modules registry did not. $mismatch",
    )
  }

  private fun projectionMismatch(
    moduleName: String,
    expectedActiveRoots: Set<Path>,
    excludedPaths: Set<Path>,
  ): String? {
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
