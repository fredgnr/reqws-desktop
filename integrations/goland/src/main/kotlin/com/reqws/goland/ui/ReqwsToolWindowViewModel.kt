package com.reqws.goland.ui

import com.reqws.goland.manifest.RepositoryAvailability
import com.reqws.goland.project.ReqwsLifecycleState
import com.reqws.goland.project.ReqwsProjectState

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
  val preservedSnapshot: Boolean,
  val syncEnabled: Boolean,
  val openManifestEnabled: Boolean,
  val copyDiagnosticsEnabled: Boolean,
) {
  companion object {
    fun from(state: ReqwsProjectState): ReqwsToolWindowViewModel {
      val snapshot = state.snapshot
      val lifecycle = state.lifecycle
      return ReqwsToolWindowViewModel(
        visible = lifecycle != ReqwsLifecycleState.DISPOSED &&
          (snapshot != null ||
            (lifecycle != ReqwsLifecycleState.INACTIVE && lifecycle != ReqwsLifecycleState.READING)),
        workspaceName = snapshot?.manifest?.name,
        featureBranch = snapshot?.manifest?.featureBranch,
        statusKey = lifecycle.resourceKey(),
        statusDetailKey = if (lifecycle == ReqwsLifecycleState.SAFE_MODE_BLOCKED) {
          "message.safeModeHint"
        } else {
          null
        },
        statusTone = lifecycle.statusTone(),
        repositories = snapshot?.repositories.orEmpty().map { repository ->
          ReqwsRepositoryViewModel(
            name = repository.repository.name,
            statusKey = when (repository.availability) {
              RepositoryAvailability.PRESENT -> "repository.active"
              RepositoryAvailability.MISSING -> "repository.missing"
            },
            statusTone = when (repository.availability) {
              RepositoryAvailability.PRESENT -> ReqwsStatusTone.SUCCESS
              RepositoryAvailability.MISSING -> ReqwsStatusTone.WARNING
            },
          )
        },
        digest = state.lastAppliedDigest?.take(DIGEST_DISPLAY_LENGTH),
        errorCode = state.lastError?.code,
        preservedSnapshot = state.lastError != null && snapshot != null,
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
