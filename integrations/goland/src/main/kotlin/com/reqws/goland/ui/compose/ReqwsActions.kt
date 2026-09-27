package com.reqws.goland.ui.compose

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.reqws.goland.ReqwsBundle
import com.reqws.goland.ui.state.ReqwsUiAction
import com.reqws.goland.ui.state.ReqwsUiState
import org.jetbrains.jewel.ui.component.DefaultButton
import org.jetbrains.jewel.ui.component.OutlinedButton
import org.jetbrains.jewel.ui.component.Text

@Composable
internal fun ReqwsActions(state: ReqwsUiState, onAction: (ReqwsUiAction) -> Unit) {
  Column(Modifier.fillMaxWidth().padding(12.dp).testTag("reqws.actions"),
    verticalArrangement = Arrangement.spacedBy(8.dp)) {
    if (state.diagnosticsCopied) {
      Text(ReqwsBundle.message("message.diagnosticsCopied"), modifier = Modifier.testTag("reqws.copyFeedback")
        .semantics { liveRegion = LiveRegionMode.Polite })
    }
    DefaultButton(onClick = { onAction(ReqwsUiAction.SyncNow) }, enabled = state.syncEnabled,
      modifier = Modifier.fillMaxWidth().testTag("reqws.sync")) {
      Text(ReqwsBundle.message("action.syncNow"))
    }
    OutlinedButton(onClick = { onAction(ReqwsUiAction.OpenManifest) }, enabled = state.openManifestEnabled,
      modifier = Modifier.fillMaxWidth().testTag("reqws.openManifest")) {
      Text(ReqwsBundle.message("action.openManifest"))
    }
    OutlinedButton(onClick = { onAction(ReqwsUiAction.CopyDiagnostics) }, enabled = state.copyDiagnosticsEnabled,
      modifier = Modifier.fillMaxWidth().testTag("reqws.copyDiagnostics")) {
      Text(ReqwsBundle.message("action.copyDiagnostics"))
    }
  }
}
