package com.reqws.goland.ui

import com.reqws.goland.manifest.RepositoryAvailability
import com.reqws.goland.project.ReqwsLifecycleState
import com.reqws.goland.project.ReqwsProjectState
import com.reqws.goland.project.ReqwsStableErrorCode
import com.reqws.goland.vcs.VcsRepositoryStatus
import com.reqws.goland.vcs.VcsWorkspaceDiagnosticCode

data class ReqwsRepositoryViewModel(
  val name: String,
  val statusKey: String,
  val statusTone: ReqwsStatusTone,
)

enum class ReqwsStatusTone {
  NEUTRAL,
  INFO,
  SUCCESS,
  WARNING,
  ERROR,
}

data class ReqwsToolWindowViewModel(
  val visible: Boolean,
  val workspaceName: String?,
  val featureBranch: String?,
  val statusKey: String,
  val statusDetailKey: String?,
  val statusTone: ReqwsStatusTone,
  val repositories: List<ReqwsRepositoryViewModel>,
  val digest: String?,
  val errorCode: String?,
  val vcsDiagnosticCode: String?,
  val preservedSnapshot: Boolean,
  val syncEnabled: Boolean,
  val openManifestEnabled: Boolean,
  val copyDiagnosticsEnabled: Boolean,
) {
  companion object {
    fun from(state: ReqwsProjectState): ReqwsToolWindowViewModel {
      val snapshot = state.snapshot
      val lifecycle = state.lifecycle
      val vcsInspection = state.vcsInspection
      val repositoryVcsStatuses = vcsInspection
        ?.repositoryStatuses
        .orEmpty()
        .associate { it.repositoryIndex to it.status }
      val showVcsDiagnostics = lifecycle == ReqwsLifecycleState.SYNCHRONIZED ||
        lifecycle == ReqwsLifecycleState.DEGRADED
      val vcsDiagnosticCode = vcsInspection?.stableErrorCode().takeIf {
        showVcsDiagnostics
      }
      val gitIntegrationUnavailable = vcsInspection?.workspaceDiagnostics.orEmpty()
        .contains(VcsWorkspaceDiagnosticCode.GIT_PLUGIN_UNAVAILABLE)
      val inspectionFailed = vcsInspection?.workspaceDiagnostics.orEmpty()
        .contains(VcsWorkspaceDiagnosticCode.INSPECTION_FAILED)
      return ReqwsToolWindowViewModel(
        visible = lifecycle != ReqwsLifecycleState.DISPOSED &&
          (snapshot != null ||
            (lifecycle != ReqwsLifecycleState.INACTIVE && lifecycle != ReqwsLifecycleState.READING)),
        workspaceName = snapshot?.manifest?.name,
        featureBranch = snapshot?.manifest?.featureBranch,
        statusKey = lifecycle.resourceKey(),
        statusDetailKey = when {
          lifecycle == ReqwsLifecycleState.SAFE_MODE_BLOCKED -> "message.safeModeHint"
          showVcsDiagnostics && gitIntegrationUnavailable ->
            "message.vcsGitIntegrationUnavailable"
          showVcsDiagnostics && inspectionFailed -> "message.vcsInspectionFailed"
          showVcsDiagnostics && vcsInspection?.requiresManualConfiguration == true ->
            "message.vcsManualConfigurationRequired"
          else -> null
        },
        statusTone = lifecycle.statusTone(),
        repositories = snapshot?.repositories.orEmpty().mapIndexed { index, repository ->
          val vcsStatus = repositoryVcsStatuses[index]
          ReqwsRepositoryViewModel(
            name = repository.repository.name,
            statusKey = when {
              repository.availability == RepositoryAvailability.MISSING ||
                vcsStatus == VcsRepositoryStatus.MISSING_DIRECTORY -> "repository.missing"
              gitIntegrationUnavailable || inspectionFailed -> "repository.gitStatusUnavailable"
              vcsInspection != null && vcsStatus == null -> "repository.gitStatusUnavailable"
              vcsStatus == VcsRepositoryStatus.NOT_GIT -> "repository.notGit"
              vcsStatus == VcsRepositoryStatus.NOT_CONFIGURED -> "repository.gitRootMissing"
              vcsStatus == VcsRepositoryStatus.WRONG_VCS ||
                vcsStatus == VcsRepositoryStatus.DUPLICATE -> "repository.gitRootConflict"
              else -> "repository.active"
            },
            statusTone = when {
              repository.availability == RepositoryAvailability.MISSING ||
                vcsStatus == VcsRepositoryStatus.MISSING_DIRECTORY -> ReqwsStatusTone.WARNING
              gitIntegrationUnavailable || inspectionFailed -> ReqwsStatusTone.WARNING
              vcsInspection != null && vcsStatus == null -> ReqwsStatusTone.WARNING
              vcsStatus == VcsRepositoryStatus.CONFIGURED || vcsInspection == null ->
                ReqwsStatusTone.SUCCESS
              else -> ReqwsStatusTone.WARNING
            },
          )
        },
        digest = state.lastAppliedDigest?.take(DIGEST_DISPLAY_LENGTH),
        errorCode = state.lastError?.code,
        vcsDiagnosticCode = vcsDiagnosticCode,
        preservedSnapshot = lifecycle == ReqwsLifecycleState.ERROR &&
          state.lastError != null && snapshot != null,
        syncEnabled = lifecycle != ReqwsLifecycleState.INACTIVE &&
          lifecycle != ReqwsLifecycleState.READING &&
          lifecycle != ReqwsLifecycleState.DISPOSED,
        openManifestEnabled = lifecycle != ReqwsLifecycleState.INACTIVE &&
          lifecycle != ReqwsLifecycleState.DISPOSED,
        copyDiagnosticsEnabled = lifecycle != ReqwsLifecycleState.INACTIVE &&
          lifecycle != ReqwsLifecycleState.DISPOSED,
      )
    }

    private const val DIGEST_DISPLAY_LENGTH = 12
  }
}

private fun ReqwsLifecycleState.resourceKey(): String = when (this) {
  ReqwsLifecycleState.INACTIVE -> "state.inactive"
  ReqwsLifecycleState.READING -> "state.reading"
  ReqwsLifecycleState.SAFE_MODE_BLOCKED -> "state.safeModeBlocked"
  ReqwsLifecycleState.SYNCHRONIZING -> "state.synchronizing"
  ReqwsLifecycleState.SYNCHRONIZED -> "state.synchronized"
  ReqwsLifecycleState.DEGRADED -> "state.degraded"
  ReqwsLifecycleState.ERROR -> "state.error"
  ReqwsLifecycleState.DISPOSED -> "state.disposed"
}

private fun ReqwsLifecycleState.statusTone(): ReqwsStatusTone = when (this) {
  ReqwsLifecycleState.INACTIVE,
  ReqwsLifecycleState.DISPOSED
  -> ReqwsStatusTone.NEUTRAL
  ReqwsLifecycleState.READING,
  ReqwsLifecycleState.SYNCHRONIZING
  -> ReqwsStatusTone.INFO
  ReqwsLifecycleState.SYNCHRONIZED -> ReqwsStatusTone.SUCCESS
  ReqwsLifecycleState.SAFE_MODE_BLOCKED,
  ReqwsLifecycleState.DEGRADED
  -> ReqwsStatusTone.WARNING
  ReqwsLifecycleState.ERROR -> ReqwsStatusTone.ERROR
}
