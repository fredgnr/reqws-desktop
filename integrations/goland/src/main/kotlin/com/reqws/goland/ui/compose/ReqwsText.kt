package com.reqws.goland.ui.compose

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.reqws.goland.ui.state.ReqwsStatusTone
import org.jetbrains.jewel.foundation.theme.LocalColorPalette
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.component.Tooltip

@Composable
internal fun ReqwsCard(tag: String, content: @Composable ColumnScope.() -> Unit) {
  val shape = RoundedCornerShape(8.dp)
  Column(
    Modifier.fillMaxWidth().background(JewelTheme.globalColors.panelBackground, shape)
      .border(1.dp, JewelTheme.globalColors.borders.normal, shape).padding(12.dp).testTag(tag),
    verticalArrangement = Arrangement.spacedBy(8.dp),
    content = content,
  )
}

/** Compose Text renders literal strings; the complete value remains in semantics and tooltip. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun ReqwsLiteralText(
  value: String,
  tag: String,
  modifier: Modifier = Modifier,
  description: String = value,
  maxLines: Int = 2,
  tooltipEnabled: Boolean = value.isNotEmpty(),
  color: Color = JewelTheme.globalColors.text.normal,
) {
  Tooltip(
    enabled = tooltipEnabled,
    modifier = modifier,
    tooltip = { ReqwsFullText(value, "$tag.tooltip") },
  ) {
    Text(
      value,
      modifier = Modifier.fillMaxWidth().testTag(tag).semantics { contentDescription = description },
      maxLines = maxLines,
      overflow = TextOverflow.Ellipsis,
      color = color,
    )
  }
}

@Composable
internal fun ReqwsFullText(value: String, tag: String) {
  // Height is bounded too: arbitrarily long names never create a screen-sized tooltip.
  Text(value, modifier = Modifier.widthIn(max = 320.dp).heightIn(max = 240.dp)
    .verticalScroll(rememberScrollState()).testTag(tag))
}

@Composable
internal fun ReqwsStatus(text: String, tone: ReqwsStatusTone, tag: String) {
  val colors = JewelTheme.globalColors
  val color = when (tone) {
    ReqwsStatusTone.ERROR -> colors.text.error
    ReqwsStatusTone.WARNING -> colors.text.warning
    ReqwsStatusTone.INFO -> colors.outlines.focused
    ReqwsStatusTone.SUCCESS -> LocalColorPalette.current.greenOrNull(if (JewelTheme.isDark) 6 else 5) ?: colors.text.normal
    ReqwsStatusTone.NEUTRAL -> colors.text.normal
  }
  Row(
    Modifier.fillMaxWidth().testTag(tag).semantics(mergeDescendants = true) { stateDescription = text },
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(6.dp),
  ) {
    Canvas(Modifier.size(6.dp)) { drawCircle(color) }
    ReqwsLiteralText(text, "$tag.text", Modifier.weight(1f), color = color)
  }
}
