package com.reqws.goland.vcs

import com.intellij.openapi.vcs.VcsDirectoryMapping

internal const val GIT_VCS_NAME = "Git"

/**
 * CREATED mappings may be removed after their persisted ownership still matches exactly.
 * BORROWED mappings only record that an existing user mapping satisfied the desired state.
 */
internal enum class VcsMappingOwnershipKind {
  CREATED,
  BORROWED,
}

/** Persist only this workspace-relative value and the ownership kind; never persist remotes. */
internal data class VcsMappingOwnership(
  val relativeDirectory: String,
  val kind: VcsMappingOwnershipKind,
)

/**
 * A durable transition tombstone. It deliberately carries no deletion authority: only the live
 * apply that created the random operation token may finish its already-validated mapping write.
 */
internal data class VcsMappingPendingOwnership(
  val relativeDirectory: String,
  val operationToken: String,
)

internal data class VcsMappingOwnershipState(
  val stableMappings: List<VcsMappingOwnership>,
  val pendingAdds: List<VcsMappingPendingOwnership> = emptyList(),
  val pendingRemovals: List<VcsMappingPendingOwnership> = emptyList(),
)

internal enum class DesiredVcsRootAvailability {
  PRESENT_GIT,
  MISSING,
  NOT_GIT_REPOSITORY,
}

internal data class DesiredVcsRoot(
  val repositoryIndex: Int,
  val relativeDirectory: String,
  val directory: String?,
  val availability: DesiredVcsRootAvailability,
)

internal data class CurrentVcsMapping(
  val index: Int,
  val directory: String,
  val vcs: String,
  val hasRootSettings: Boolean,
  val lexicalIdentity: String?,
  val canonicalIdentity: String?,
)

internal enum class VcsMappingDiagnosticCode {
  REPOSITORY_MISSING,
  REPOSITORY_NOT_GIT,
  OWNERSHIP_CONFLICT,
  DUPLICATE_MAPPING,
}

internal data class VcsMappingDiagnostic(
  val code: VcsMappingDiagnosticCode,
  val repositoryIndex: Int? = null,
) {
  override fun toString(): String =
    "VcsMappingDiagnostic(code=$code, repositoryIndex=$repositoryIndex)"
}

internal data class VcsMappingAddition(
  val directory: String,
  val relativeDirectory: String,
)

internal data class VcsMappingPlan(
  val additions: List<VcsMappingAddition>,
  val removalIndices: Set<Int>,
  val nextOwnership: List<VcsMappingOwnership>,
  val diagnostics: List<VcsMappingDiagnostic>,
) {
  val mappingsChanged: Boolean
    get() = additions.isNotEmpty() || removalIndices.isNotEmpty()

  val degraded: Boolean
    get() = diagnostics.isNotEmpty()
}

internal enum class VcsMappingApplyErrorCode {
  SAFE_MODE_BLOCKED,
  PROJECT_DISPOSED,
  GIT_PLUGIN_UNAVAILABLE,
  VCS_MAPPING_APPLY_FAILED,
}

internal enum class VcsMappingApplyStage {
  AVAILABILITY,
  MAPPINGS,
  OWNERSHIP,
  REFRESH,
}

internal class VcsMappingApplyException(
  val code: VcsMappingApplyErrorCode,
  val stage: VcsMappingApplyStage,
  val mappingsCommitted: Boolean,
  val ownershipCommitted: Boolean,
  cause: Throwable? = null,
) : RuntimeException(code.name, cause) {
  override fun toString(): String =
    "VcsMappingApplyException(code=$code, stage=$stage, " +
      "mappingsCommitted=$mappingsCommitted, ownershipCommitted=$ownershipCommitted)"
}

internal data class VcsMappingApplyResult(
  val plan: VcsMappingPlan,
  val mappingsCommitted: Boolean,
  val ownershipCommitted: Boolean,
  val refreshed: Boolean,
)

internal data class VersionedVcsMappings(
  val revision: Long,
  val mappings: List<VcsDirectoryMapping>,
  val quiescent: Boolean = true,
  val pendingExternal: ExternalVcsMappings? = null,
)

internal data class ExternalVcsMappings(
  val revision: Long,
  val mappings: List<VcsDirectoryMapping>,
)

/**
 * Mirrors the observable storage semantics of GoLand 261/262 without depending on its internal
 * NewMappings implementation: the last complete mapping for an exact directory wins, then the
 * retained mappings are sorted by the directory string. The winning object is preserved so VCS
 * specific root settings are never reconstructed or dropped.
 */
internal fun canonicalizeVcsMappings(
  mappings: List<VcsDirectoryMapping>,
): List<VcsDirectoryMapping> {
  val lastByDirectory = linkedMapOf<String, VcsDirectoryMapping>()
  mappings.forEach { mapping -> lastByDirectory[mapping.directory] = mapping }
  return lastByDirectory.values.sortedBy(VcsDirectoryMapping::getDirectory)
}

internal fun ExternalVcsMappings.platformCanonicalized(): ExternalVcsMappings = copy(
  mappings = canonicalizeVcsMappings(mappings),
)

internal fun VersionedVcsMappings.platformCanonicalized(): VersionedVcsMappings = copy(
  mappings = canonicalizeVcsMappings(mappings),
  pendingExternal = pendingExternal?.platformCanonicalized(),
)

internal fun interface VcsMappingOwnershipRecorder {
  /**
   * Returns a commit for one immutable ownership phase. The adapter invokes persistence only on
   * its background caller, and prepares the final phase after the transition phase has completed.
   */
  fun prepare(state: VcsMappingOwnershipState): VcsMappingOwnershipCommit
}

internal fun interface VcsMappingOwnershipCommit {
  /** Must atomically persist and strictly read back the prepared state outside EDT. */
  fun persistAndVerify()
}
