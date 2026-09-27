package com.reqws.goland.ui.compose

import androidx.compose.foundation.ExperimentalFoundationApi
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.sp
import org.jetbrains.jewel.foundation.theme.LocalTextStyle
import org.jetbrains.jewel.ui.component.Icon
import org.jetbrains.jewel.ui.icons.AllIconsKeys
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
import org.jetbrains.jewel.ui.component.Popup
import org.jetbrains.jewel.ui.component.styling.LocalTooltipStyle
import androidx.compose.runtime.*
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.key
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import kotlinx.coroutines.delay

@Composable
internal fun ReqwsCard(tag: String, padding: Dp = 12.dp, spacing: Dp = 8.dp, content: @Composable ColumnScope.() -> Unit) {
  val shape = RoundedCornerShape(4.dp)
  Column(
    Modifier.fillMaxWidth().clip(shape).background(JewelTheme.globalColors.panelBackground, shape)
      .border(1.dp, JewelTheme.globalColors.borders.normal, shape).padding(padding).testTag(tag),
    verticalArrangement = Arrangement.spacedBy(spacing),
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
  style: TextStyle = LocalTextStyle.current,
  fillWidth: Boolean = true,
) {
  ReqwsHoverTooltip(
    enabled = tooltipEnabled,
    modifier = modifier,
    tooltip = { ReqwsFullText(value, "$tag.tooltip") },
  ) {
    Text(
      value,
      modifier = (if (fillWidth) Modifier.fillMaxWidth() else Modifier).testTag(tag).semantics { contentDescription = description },
      maxLines = maxLines,
      overflow = TextOverflow.Ellipsis,
      color = color,
      style = style,
    )
  }
}

/** A bounded full-text popup must stay open while the pointer enters it to scroll. */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
internal fun ReqwsHoverTooltip(
  tooltip: @Composable () -> Unit,
  modifier: Modifier = Modifier,
  enabled: Boolean = true,
  content: @Composable () -> Unit,
) {
  val style = LocalTooltipStyle.current
  var anchorHovered by remember { mutableStateOf(false) }
  var popupHovered by remember { mutableStateOf(false) }
  var visible by remember { mutableStateOf(false) }
  LaunchedEffect(enabled, anchorHovered, popupHovered) {
    if (!enabled) visible = false
    else if (anchorHovered) {
      delay(style.metrics.showDelay.inWholeMilliseconds.coerceAtLeast(0))
      visible = true
    } else if (!popupHovered) {
      // Allow the pointer to cross the small gap between the source and popup.
      delay(350)
      visible = false
    }
  }
  val gap = with(LocalDensity.current) { 6.dp.roundToPx() }
  val position = remember(gap) {
    object : PopupPositionProvider {
      override fun calculatePosition(anchorBounds: IntRect, windowSize: IntSize,
        layoutDirection: LayoutDirection, popupContentSize: IntSize): IntOffset {
        val preferredX = if (layoutDirection == LayoutDirection.Ltr) anchorBounds.left else anchorBounds.right - popupContentSize.width
        val below = anchorBounds.bottom + gap
        val preferredY = if (below + popupContentSize.height <= windowSize.height) below else anchorBounds.top - popupContentSize.height - gap
        return IntOffset(preferredX.coerceIn(0, (windowSize.width - popupContentSize.width).coerceAtLeast(0)),
          preferredY.coerceIn(0, (windowSize.height - popupContentSize.height).coerceAtLeast(0)))
      }
    }
  }
  fun dismiss() { visible = false; anchorHovered = false; popupHovered = false }
  Box(modifier.onPointerEvent(PointerEventType.Enter) { anchorHovered = true }
    .onPointerEvent(PointerEventType.Exit) { anchorHovered = false }) {
    content()
    if (visible && enabled) {
      val shape = RoundedCornerShape(style.metrics.cornerSize)
      Popup(popupPositionProvider = position, cornerSize = style.metrics.cornerSize,
        onDismissRequest = ::dismiss, properties = PopupProperties(focusable = false, dismissOnClickOutside = true),
        onPreviewKeyEvent = { if (it.key == Key.Escape) { dismiss(); true } else false }) {
        Box(Modifier.onPointerEvent(PointerEventType.Enter) { popupHovered = true }
          .onPointerEvent(PointerEventType.Exit) { popupHovered = false }
          .clip(shape).background(style.colors.background, shape)
          .border(style.metrics.borderWidth, style.colors.border, shape).padding(style.metrics.contentPadding)) {
          CompositionLocalProvider(LocalTextStyle provides LocalTextStyle.current.copy(color = style.colors.content)) { tooltip() }
        }
      }
    }
  }
}

@Composable
internal fun ReqwsFullText(value: String, tag: String) {
  // Height is bounded too: arbitrarily long names never create a screen-sized tooltip.
  Text(value, modifier = Modifier.widthIn(max = 320.dp).heightIn(max = 240.dp)
    .verticalScroll(rememberScrollState()).testTag(tag))
}

@Composable
internal fun reqwsTitleTextStyle(): TextStyle = LocalTextStyle.current.let {
  it.copy(fontSize = (it.fontSize.value + 3f).sp, fontWeight = FontWeight.Medium)
}

@Composable
internal fun reqwsSmallTextStyle(): TextStyle = LocalTextStyle.current.let {
  it.copy(fontSize = (it.fontSize.value - 2f).coerceAtLeast(9f).sp)
}

@Composable
internal fun ReqwsStatus(text: String, tone: ReqwsStatusTone, tag: String,
  modifier: Modifier = Modifier, pill: Boolean = false, tooltipEnabled: Boolean = true, textColor: Color? = null) {
  val colors = JewelTheme.globalColors
  val color = when (tone) {
    ReqwsStatusTone.ERROR -> colors.text.error
    ReqwsStatusTone.WARNING -> colors.text.warning
    ReqwsStatusTone.INFO -> colors.text.info
    ReqwsStatusTone.SUCCESS -> LocalColorPalette.current.greenOrNull(if (JewelTheme.isDark) 6 else 5) ?: colors.text.normal
    ReqwsStatusTone.NEUTRAL -> colors.text.normal
  }
  val icon = when (tone) {
    ReqwsStatusTone.NEUTRAL, ReqwsStatusTone.INFO -> AllIconsKeys.General.Information
    ReqwsStatusTone.SUCCESS -> AllIconsKeys.General.InspectionsOK
    ReqwsStatusTone.WARNING -> AllIconsKeys.General.Warning
    ReqwsStatusTone.ERROR -> AllIconsKeys.General.Error
  }
  val shape = RoundedCornerShape(4.dp)
  val surface = if (pill) Modifier.background(color.copy(alpha = .12f).compositeOver(colors.panelBackground), shape)
    .border(1.dp, color, shape).padding(horizontal = 8.dp, vertical = 2.dp) else Modifier
  Row(
    modifier.then(surface).testTag(tag).semantics(mergeDescendants = true) { stateDescription = text },
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(5.dp),
  ) {
    Icon(icon, contentDescription = null, modifier = Modifier.size(16.dp))
    ReqwsLiteralText(text, "$tag.text", maxLines = 1, fillWidth = false,
      tooltipEnabled = tooltipEnabled, color = textColor ?: if (pill) color else colors.text.normal)
  }
}
