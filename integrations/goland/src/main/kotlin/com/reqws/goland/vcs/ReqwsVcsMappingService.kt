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
   * Filesystem identity/ownership preparation and Git refresh stay on a background dispatcher.
   * The adapter moves only its final full-list equality check, pure in-memory ownership commits,
   * and mapping commit onto EDT so the VCS Settings writer cannot interleave with replacement.
   */
  suspend fun apply(
    snapshot: ManifestSnapshot,
    isServiceDisposed: () -> Boolean = { false },
  ): VcsMappingApplyResult = withContext(Dispatchers.IO) {
    val isDisposed = { project.isDisposed || isServiceDisposed() }
    ensureMutationAllowed(isDisposed)
    val loadedOwnership = ownershipState.readForProject(snapshot.canonicalProjectRoot)
    val result = IntellijVcsMappingAdapter(
      platform = IntellijVcsMappingPlatform(project),
      isProjectDisposed = isDisposed,
      isProjectTrusted = { TrustedProjects.isProjectTrusted(project) },
    ).apply(
      snapshot = snapshot,
      currentOwnership = loadedOwnership.ownership,
      ownershipRecorder = VcsMappingOwnershipRecorder { nextState ->
        VcsMappingOwnershipCommit {
          // Prepare immediately before persistence so the generation check chains transition and
          // final checkpoints instead of letting two precomputed writes share one base version.
          val replacement = ownershipState.prepareReplacementForProject(
            snapshot.canonicalProjectRoot,
            nextState,
          )
          ownershipState.persistPreparedReplacement(replacement)
        }
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

  private fun ensureMutationAllowed(isDisposed: () -> Boolean) {
    val code = when {
      isDisposed() -> VcsMappingApplyErrorCode.PROJECT_DISPOSED
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
