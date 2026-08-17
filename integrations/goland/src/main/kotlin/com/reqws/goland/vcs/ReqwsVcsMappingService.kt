package com.reqws.goland.vcs

import com.intellij.ide.trustedProjects.TrustedProjects
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.reqws.goland.manifest.ManifestSnapshot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Project-level production entry point used by the sync orchestration layer. */
@Service(Service.Level.PROJECT)
internal class ReqwsVcsMappingService(private val project: Project) {
  private val ownershipState: ReqwsVcsOwnershipStateService
    get() = project.service()

  /**
   * Mapping reconciliation and Git repository refresh perform filesystem work. Platform 261's
   * setDirectoryMappings explicitly supports a background-thread update path, so keep the whole
   * operation off EDT rather than blocking a UI callback.
   */
  suspend fun apply(snapshot: ManifestSnapshot): VcsMappingApplyResult = withContext(Dispatchers.IO) {
    ensureMutationAllowed()
    val loadedOwnership = ownershipState.readForProject(snapshot.canonicalProjectRoot)
    val result = IntellijVcsMappingAdapter(
      platform = IntellijVcsMappingPlatform(project),
      isProjectDisposed = { project.isDisposed },
      isProjectTrusted = { TrustedProjects.isProjectTrusted(project) },
    ).apply(
      snapshot = snapshot,
      currentOwnership = loadedOwnership.ownership,
      ownershipRecorder = VcsMappingOwnershipRecorder { nextOwnership ->
        ownershipState.replaceForProject(snapshot.canonicalProjectRoot, nextOwnership)
      },
    )
    if (loadedOwnership.diagnostics.isEmpty()) {
      result
    } else {
      result.copy(
        plan = result.plan.copy(
          diagnostics = loadedOwnership.diagnostics + result.plan.diagnostics,
        ),
      )
    }
  }

  private fun ensureMutationAllowed() {
    val code = when {
      project.isDisposed -> VcsMappingApplyErrorCode.PROJECT_DISPOSED
      !TrustedProjects.isProjectTrusted(project) -> VcsMappingApplyErrorCode.SAFE_MODE_BLOCKED
      else -> return
    }
    throw VcsMappingApplyException(
      code = code,
      stage = VcsMappingApplyStage.AVAILABILITY,
      mappingsCommitted = false,
      ownershipCommitted = false,
    )
  }
}
