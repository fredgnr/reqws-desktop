package com.reqws.goland.ui.state

internal enum class ReqwsUiAction {
  SyncNow,
  OpenManifest,
  CopyDiagnostics,
}

internal fun ReqwsUiState.allows(action: ReqwsUiAction): Boolean = when (action) {
  ReqwsUiAction.SyncNow -> syncEnabled
  ReqwsUiAction.OpenManifest -> openManifestEnabled
  ReqwsUiAction.CopyDiagnostics -> copyDiagnosticsEnabled
}
