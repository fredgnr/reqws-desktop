package com.reqws.goland.vcs

internal const val GIT_VCS_NAME = "Git"

enum class VcsRepositoryStatus {
  CONFIGURED,
  MISSING_DIRECTORY,
  NOT_GIT,
  NOT_CONFIGURED,
  WRONG_VCS,
  DUPLICATE,
}

enum class VcsWorkspaceDiagnosticCode {
  WORKSPACE_WIDE_GIT_ROOT,
  INACTIVE_GIT_ROOT,
  GIT_PLUGIN_UNAVAILABLE,
  INSPECTION_FAILED,
}

data class VcsRepositoryInspection(
  val repositoryIndex: Int,
  val status: VcsRepositoryStatus,
)

data class VcsRootInspection(
  val repositoryStatuses: List<VcsRepositoryInspection>,
  val workspaceDiagnostics: List<VcsWorkspaceDiagnosticCode>,
) {
  val degraded: Boolean
    get() = repositoryStatuses.any { it.status != VcsRepositoryStatus.CONFIGURED } ||
      workspaceDiagnostics.isNotEmpty()

  val requiresManualConfiguration: Boolean
    get() = repositoryStatuses.any {
      it.status == VcsRepositoryStatus.NOT_CONFIGURED ||
        it.status == VcsRepositoryStatus.WRONG_VCS ||
        it.status == VcsRepositoryStatus.DUPLICATE
    } || workspaceDiagnostics.any {
      it == VcsWorkspaceDiagnosticCode.WORKSPACE_WIDE_GIT_ROOT ||
        it == VcsWorkspaceDiagnosticCode.INACTIVE_GIT_ROOT ||
        it == VcsWorkspaceDiagnosticCode.GIT_PLUGIN_UNAVAILABLE
    }

  fun stableErrorCode(): String? = when {
    VcsWorkspaceDiagnosticCode.INSPECTION_FAILED in workspaceDiagnostics ->
      "VCS_DIAGNOSTIC_FAILED"
    VcsWorkspaceDiagnosticCode.GIT_PLUGIN_UNAVAILABLE in workspaceDiagnostics ->
      "GIT_PLUGIN_UNAVAILABLE"
    repositoryStatuses.any { it.status == VcsRepositoryStatus.NOT_GIT } ->
      "REPOSITORY_NOT_GIT"
    repositoryStatuses.any { it.status == VcsRepositoryStatus.MISSING_DIRECTORY } ->
      "REPOSITORY_MISSING"
    requiresManualConfiguration -> "VCS_CONFIGURATION_MISMATCH"
    else -> null
  }

  companion object {
    fun inspectionFailed(): VcsRootInspection = VcsRootInspection(
      repositoryStatuses = emptyList(),
      workspaceDiagnostics = listOf(VcsWorkspaceDiagnosticCode.INSPECTION_FAILED),
    )
  }
}

internal data class ObservedVcsMapping(
  val directory: String,
  val vcs: String,
  val hasRootSettings: Boolean,
)
