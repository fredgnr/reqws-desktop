package com.reqws.goland.ui.platform

import com.reqws.goland.project.ReqwsProjectState
import com.reqws.goland.ui.state.ReqwsUiAction
import com.reqws.goland.ui.state.ReqwsUiStateMapper
import com.reqws.goland.ui.state.allows

/** The visible enabled state is a hint; execute against one fresh domain snapshot. */
internal class ReqwsActionDispatcher(
  private val isUsable: () -> Boolean,
  private val currentState: () -> ReqwsProjectState,
  private val sync: () -> Unit,
  private val openManifest: () -> Unit,
  private val copyDiagnostics: (ReqwsProjectState) -> Unit,
) {
  fun dispatch(action: ReqwsUiAction): ReqwsProjectState? {
    if (!isUsable()) return null
    val snapshot = currentState()
    if (!ReqwsUiStateMapper.map(snapshot).allows(action) || !isUsable()) return null
    when (action) {
      ReqwsUiAction.SyncNow -> sync()
      ReqwsUiAction.OpenManifest -> openManifest()
      ReqwsUiAction.CopyDiagnostics -> {
        copyDiagnostics(snapshot)
        return snapshot
      }
    }
    return null
  }
}
