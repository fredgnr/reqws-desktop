package com.reqws.goland.projectmodel

import com.intellij.openapi.application.readAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import java.nio.file.Path
import kotlinx.coroutines.delay

internal fun interface ReqwsLiveProjectionVerifier {
  suspend fun verify(
    activeRepositoryPaths: Collection<Path>,
    excludedPaths: Collection<Path>,
  )
}

/**
 * Closes the gap between a committed Workspace Model snapshot and the live file index used by
 * Project View, Search, Go analysis, and indexing. A projection is not successful until those
 * public file-index APIs expose the same active/excluded boundary.
 */
internal class PlatformReqwsLiveProjectionVerifier(
  private val project: Project,
  private val attempts: Int = DEFAULT_ATTEMPTS,
  private val retryDelayMillis: Long = DEFAULT_RETRY_DELAY_MILLIS,
) : ReqwsLiveProjectionVerifier {
  override suspend fun verify(
    activeRepositoryPaths: Collection<Path>,
    excludedPaths: Collection<Path>,
  ) {
    require(attempts > 0) { "Live projection verification requires at least one attempt." }
    val fileSystem = LocalFileSystem.getInstance()
    val activeFiles = activeRepositoryPaths
      .distinct()
      .associateWith { path -> requireVirtualFile(fileSystem, path, "active repository") }
    val excludedFiles = excludedPaths
      .distinct()
      .associateWith { path -> requireVirtualFile(fileSystem, path, "excluded path") }

    repeat(attempts) { attempt ->
      if (project.isDisposed) {
        throw ProjectModelApplyException(
          ProjectModelErrorCode.PROJECT_DISPOSED,
          "Project was disposed before the ReqWS live projection converged.",
        )
      }
      val mismatch = readAction {
        val fileIndex = ProjectFileIndex.getInstance(project)
        activeFiles.entries.firstOrNull { (_, file) ->
          fileIndex.isExcluded(file) || !fileIndex.isInContent(file)
        }?.let { (path, _) ->
          "Active repository is not in live project content: ${path.fileName}"
        }
          ?: excludedFiles.entries.firstOrNull { (_, file) -> !fileIndex.isExcluded(file) }
            ?.let { (path, _) ->
              "ReqWS excluded path remains live project content: ${path.fileName}"
            }
      }
      if (mismatch == null) return
      if (attempt + 1 < attempts) delay(retryDelayMillis)
      else throw ProjectModelApplyException(
        ProjectModelErrorCode.LIVE_FILE_INDEX_NOT_CONVERGED,
        "The ReqWS Workspace Model committed, but the live project file index did not converge. " +
          mismatch,
      )
    }
  }

  private fun requireVirtualFile(
    fileSystem: LocalFileSystem,
    path: Path,
    role: String,
  ): VirtualFile = fileSystem.refreshAndFindFileByNioFile(path)
    ?: throw ProjectModelApplyException(
      ProjectModelErrorCode.LIVE_FILE_INDEX_NOT_CONVERGED,
      "Unable to resolve a ReqWS $role in the live VFS.",
    )

  private companion object {
    const val DEFAULT_ATTEMPTS = 20
    const val DEFAULT_RETRY_DELAY_MILLIS = 50L
  }
}
