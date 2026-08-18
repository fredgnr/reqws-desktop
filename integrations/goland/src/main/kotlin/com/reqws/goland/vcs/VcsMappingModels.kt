package com.reqws.goland.vcs

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

internal fun interface VcsMappingOwnershipRecorder {
  /**
   * Performs any path validation and returns a pure in-memory commit prepared for the supplied
   * ownership. The adapter invokes this before entering the serialized VCS mapping write context.
   */
  fun prepare(ownership: List<VcsMappingOwnership>): VcsMappingOwnershipCommit
}

internal fun interface VcsMappingOwnershipCommit {
  /** Must update in-memory ownership synchronously without filesystem access. */
  fun commit()
}
