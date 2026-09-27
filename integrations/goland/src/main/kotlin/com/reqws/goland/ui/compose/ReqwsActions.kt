package com.reqws.goland.ui.compose

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.DpSize
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
import org.jetbrains.jewel.ui.component.styling.ButtonMetrics
import org.jetbrains.jewel.ui.component.styling.ButtonStyle
import org.jetbrains.jewel.ui.component.styling.LocalDefaultButtonStyle
import org.jetbrains.jewel.ui.component.styling.LocalOutlinedButtonStyle

@Composable
internal fun ReqwsActions(state: ReqwsUiState, onAction: (ReqwsUiAction) -> Unit) {
  // Jewel fixes button content height to its metric. Increase that metric with
  // font scaling so the host's larger text is not clipped inside a fitting button.
  val fontScale = LocalDensity.current.fontScale.coerceAtLeast(1f)
  val primary = LocalDefaultButtonStyle.current.withFontScale(fontScale)
  val secondary = LocalOutlinedButtonStyle.current.withFontScale(fontScale)
  Column(Modifier.fillMaxWidth().padding(12.dp).testTag("reqws.actions"),
    verticalArrangement = Arrangement.spacedBy(8.dp)) {
    if (state.diagnosticsCopied) {
      Text(ReqwsBundle.message("message.diagnosticsCopied"), modifier = Modifier.testTag("reqws.copyFeedback")
        .semantics { liveRegion = LiveRegionMode.Polite })
    }
    DefaultButton(style = primary, onClick = { onAction(ReqwsUiAction.SyncNow) }, enabled = state.syncEnabled,
      modifier = Modifier.fillMaxWidth().testTag("reqws.sync")) {
      Text(ReqwsBundle.message("action.syncNow"), softWrap = true, maxLines = Int.MAX_VALUE)
    }
    OutlinedButton(style = secondary, onClick = { onAction(ReqwsUiAction.OpenManifest) }, enabled = state.openManifestEnabled,
      modifier = Modifier.fillMaxWidth().testTag("reqws.openManifest")) {
      Text(ReqwsBundle.message("action.openManifest"), softWrap = true, maxLines = Int.MAX_VALUE)
    }
    OutlinedButton(style = secondary, onClick = { onAction(ReqwsUiAction.CopyDiagnostics) }, enabled = state.copyDiagnosticsEnabled,
      modifier = Modifier.fillMaxWidth().testTag("reqws.copyDiagnostics")) {
      Text(ReqwsBundle.message("action.copyDiagnostics"), softWrap = true, maxLines = Int.MAX_VALUE)
    }
  }
}

private fun ButtonStyle.withFontScale(scale: Float): ButtonStyle = if (scale == 1f) this else ButtonStyle(
  colors,
  ButtonMetrics(metrics.cornerSize, metrics.padding,
    DpSize(metrics.minSize.width, metrics.minSize.height * scale), metrics.borderWidth, metrics.focusOutlineExpand),
  focusOutlineAlignment,
)
