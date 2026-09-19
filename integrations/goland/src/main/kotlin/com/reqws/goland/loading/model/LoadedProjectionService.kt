package com.reqws.goland.loading.model

import com.intellij.ide.trustedProjects.TrustedProjects
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.application.readAction
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.vfs.LocalFileSystem
import com.reqws.goland.loading.contract.LoadingSnapshot
import com.reqws.goland.loading.contract.LoadingSnapshotReader
import com.reqws.goland.loading.shell.ShellPresentationCache
import com.reqws.goland.projectmodel.ProjectModelApplyException
import com.reqws.goland.projectmodel.ProjectModelErrorCode
import java.util.concurrent.atomic.AtomicReference

@Service(Service.Level.PROJECT)
internal class LoadedProjectionService(private val project: Project) {
  private val lastResult = AtomicReference<Pair<String, ManagedRootsResult>?>()
  private val reader = LoadingSnapshotReader()
  private val observedMetadata = java.util.concurrent.atomic.AtomicBoolean(false)

  suspend fun apply(snapshot: LoadingSnapshot, current: () -> Boolean) {
    val allowed = { current() && !project.isDisposed && TrustedProjects.isProjectTrusted(project) }
    reader.verifyCurrent(snapshot)
    val presentation = project.service<ShellPresentationCache>()
    try {
      presentation.publish(snapshot.binding, allowed)
      val result = ManagedRootsAdapter(project, allowed, observedMetadata = observedMetadata).apply(snapshot)
      reader.verifyCurrent(snapshot)
      if (!allowed()) throw kotlinx.coroutines.CancellationException("Loading candidate is no longer current.")
      val shellFile = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(snapshot.binding.shell)
        ?: throw ProjectModelApplyException(ProjectModelErrorCode.LIVE_FILE_INDEX_NOT_CONVERGED, "Missing shell in VFS.")
      val excluded = readAction {
        val index = ProjectFileIndex.getInstance(project)
        index.isExcluded(shellFile) && !index.isInContent(shellFile)
      }
      if (!excluded) throw ProjectModelApplyException(ProjectModelErrorCode.LIVE_FILE_INDEX_NOT_CONVERGED, "The shell remains in project content.")
      reader.verifyCurrent(snapshot)
      if (!allowed()) throw kotlinx.coroutines.CancellationException("Loading candidate is no longer current.")
      lastResult.set(snapshot.digest to result)
    } catch (failure: Exception) {
      // A failed new binding must not keep hiding a shell owned by a different identity.
      try {
        kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
          presentation.publish(null, current)
        }
      } catch (restoreFailure: Exception) { failure.addSuppressed(restoreFailure) }
      throw failure
    }
  }

  fun coverageFor(digest: String): Set<String> = lastResult.get()?.takeIf { it.first == digest }?.second?.userCoverage.orEmpty()
}
