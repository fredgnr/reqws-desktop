package com.reqws.goland.ui.state

import com.reqws.goland.project.ReqwsLifecycleState

data class ReqwsRepositoryUiState(
  val catalogRepositoryId: String,
  val name: String,
  val statusKey: String,
  val statusTone: ReqwsStatusTone,
  val statusDetailKey: String? = null,
)

enum class ReqwsStatusTone {
  NEUTRAL,
  INFO,
  SUCCESS,
  WARNING,
  ERROR,
}

data class ReqwsUiState(
  val lifecycle: ReqwsLifecycleState,
  val visible: Boolean,
  val workspaceName: String?,
  val featureBranch: String?,
  val statusKey: String,
  val statusDetailKey: String?,
  val statusTone: ReqwsStatusTone,
  val repositories: List<ReqwsRepositoryUiState>,
  val digest: String?,
  val errorCode: String?,
  val errorDetailKey: String?,
  val vcsDiagnosticCode: String?,
  val preservedSnapshot: Boolean,
  val syncEnabled: Boolean,
  val openManifestEnabled: Boolean,
  val copyDiagnosticsEnabled: Boolean,
  val loadedRepositoryCount: Int,
  val diagnosticsCopied: Boolean = false,
)
