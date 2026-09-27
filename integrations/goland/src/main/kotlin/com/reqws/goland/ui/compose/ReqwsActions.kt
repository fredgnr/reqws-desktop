package com.reqws.goland.ui.compose

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.Composable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.reqws.goland.ReqwsBundle
import com.reqws.goland.ui.presentation.formatDetailsText
import com.reqws.goland.ui.state.ReqwsUiAction
import com.reqws.goland.ui.state.ReqwsUiState
import com.reqws.goland.ui.state.ReqwsStatusTone
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.component.DefaultButton
import org.jetbrains.jewel.ui.component.Link
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.component.styling.ButtonMetrics
import org.jetbrains.jewel.ui.component.styling.ButtonStyle
import org.jetbrains.jewel.ui.component.styling.LocalDefaultButtonStyle

@Composable
internal fun ReqwsActions(state: ReqwsUiState, onAction: (ReqwsUiAction) -> Unit) {
  val primary = LocalDefaultButtonStyle.current.withFontScale(LocalDensity.current.fontScale.coerceAtLeast(1f))
  val colors = JewelTheme.globalColors
  Column(Modifier.fillMaxWidth().testTag("reqws.actions")) {
    Box(Modifier.fillMaxWidth().height(1.dp).background(colors.borders.normal))
    Column(Modifier.fillMaxWidth().padding(start = 12.dp, top = 10.dp, end = 12.dp, bottom = 12.dp),
      horizontalAlignment = Alignment.CenterHorizontally) {
      val details = formatDetailsText(state)
      if (details != null) {
        ReqwsDiagnostics(state, details)
      }
      if (state.diagnosticsCopied) {
        if (details != null) Spacer(Modifier.height(4.dp))
        Text(ReqwsBundle.message("message.diagnosticsCopied"),
          modifier = Modifier.fillMaxWidth().testTag("reqws.copyFeedback").semantics { liveRegion = LiveRegionMode.Polite },
          style = reqwsSmallTextStyle(), color = colors.text.info)
      }
      if (details != null || state.diagnosticsCopied) Spacer(Modifier.height(8.dp))
      DefaultButton(style = primary, onClick = { onAction(ReqwsUiAction.SyncNow) }, enabled = state.syncEnabled,
        modifier = Modifier.fillMaxWidth().testTag("reqws.sync")) {
        Text(ReqwsBundle.message("action.syncNow"), softWrap = true, maxLines = Int.MAX_VALUE)
      }
      Spacer(Modifier.height(12.dp))
      Link(ReqwsBundle.message("action.openManifest"), onClick = { onAction(ReqwsUiAction.OpenManifest) },
        enabled = state.openManifestEnabled, modifier = Modifier.testTag("reqws.openManifest"))
      Spacer(Modifier.height(6.dp))
      Link(ReqwsBundle.message("action.copyDiagnostics"), onClick = { onAction(ReqwsUiAction.CopyDiagnostics) },
        enabled = state.copyDiagnosticsEnabled, modifier = Modifier.testTag("reqws.copyDiagnostics"))
    }
  }
}

/** Keep routine metadata compact while making actionable diagnostics readable. */
@Composable
private fun ReqwsDiagnostics(state: ReqwsUiState, details: String) {
  val colors = JewelTheme.globalColors
  val attention = state.errorCode != null || state.vcsDiagnosticCode != null ||
    state.statusTone == ReqwsStatusTone.ERROR || state.statusTone == ReqwsStatusTone.WARNING
  if (!attention) {
    ReqwsLiteralText(details, "reqws.diagnostics", maxLines = 1,
      style = reqwsSmallTextStyle(), color = colors.text.info)
    return
  }
  val accent = if (state.statusTone == ReqwsStatusTone.ERROR || state.errorCode != null) colors.text.error else colors.text.warning
  val style = reqwsSmallTextStyle().let { it.copy(lineHeight = (it.fontSize.value + 4f).sp) }
  Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min).clip(RoundedCornerShape(4.dp))
    .background(accent.copy(alpha = .06f).compositeOver(colors.panelBackground))
    .testTag("reqws.diagnosticNotice")) {
    Box(Modifier.width(2.dp).fillMaxHeight().background(accent))
    ReqwsLiteralText(details, "reqws.diagnostics", modifier = Modifier.weight(1f).padding(horizontal = 10.dp, vertical = 8.dp),
      maxLines = 3, style = style)
  }
}

// Jewel fixes its content height to the style metric. Preserve the old 36dp
// primary action and give enlarged fonts the corresponding vertical room.
private fun ButtonStyle.withFontScale(scale: Float): ButtonStyle = ButtonStyle(
  colors,
  ButtonMetrics(metrics.cornerSize, metrics.padding,
    DpSize(metrics.minSize.width, maxOf(36.dp, metrics.minSize.height) * scale), metrics.borderWidth, metrics.focusOutlineExpand),
  focusOutlineAlignment,
)
