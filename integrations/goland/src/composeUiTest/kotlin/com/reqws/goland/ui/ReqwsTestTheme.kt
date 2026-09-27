package com.reqws.goland.ui

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.shape.CornerSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.time.Duration.Companion.milliseconds
import org.jetbrains.jewel.foundation.*
import org.jetbrains.jewel.foundation.theme.*
import org.jetbrains.jewel.ui.component.styling.*

// Test-only public Jewel tokens. Production receives the IDE's bridge theme; no standalone
// theme artifact or platform application is created by component tests.
internal val testLightBackground = Color(0xfff5f5f5)
internal val testDarkBackground = Color(0xff202124)
internal val testLightPanel = Color(0xffffffff)
internal val testDarkPanel = Color(0xff2b2d30)

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
internal fun ReqwsTestTheme(dark: Boolean, content: @Composable () -> Unit) {
  val background = if (dark) testDarkBackground else testLightBackground
  val foreground = if (dark) Color.White else Color.Black
  val border = if (dark) Color.LightGray else Color.Gray
  val accent = Color(0xff477dff)
  val warning = Color(0xffbb8500)
  val error = Color(0xffe05050)
  val colors = GlobalColors(BorderColors(border, accent, border),
    OutlineColors(accent, warning, error, warning, error),
    TextColors(foreground, foreground, border, border, foreground, error, warning),
    if (dark) testDarkPanel else testLightPanel, background)
  fun button(primary: Boolean): ButtonStyle {
    val bg = SolidColor(if (primary) accent else background)
    val outline = SolidColor(border)
    val text = if (primary) Color.White else foreground
    return ButtonStyle(ButtonColors(bg, bg, bg, bg, bg, text, border, text, text, text,
      outline, outline, SolidColor(accent), outline, outline),
      ButtonMetrics(CornerSize(4.dp), PaddingValues(horizontal = 14.dp, vertical = 6.dp),
        DpSize(72.dp, 32.dp), 1.dp, 1.dp), Stroke.Alignment.Center)
  }
  OverrideDarkMode(dark) {
    CompositionLocalProvider(
      LocalGlobalColors provides colors,
      LocalGlobalMetrics provides GlobalMetrics(1.dp, 24.dp),
      LocalContentColor provides foreground,
      LocalTextStyle provides TextStyle(fontSize = 13.sp, color = foreground),
      LocalDisabledAppearanceValues provides DisabledAppearanceValues(0, 0, 100),
      LocalDefaultButtonStyle provides button(true),
      LocalOutlinedButtonStyle provides button(false),
      LocalTooltipStyle provides TooltipStyle(TooltipColors(background, foreground, border, Color.Transparent),
        TooltipMetrics.defaults(showDelay = 10.milliseconds, regularDisappearDelay = 10_000.milliseconds, fullDisappearDelay = 30_000.milliseconds), TooltipAutoHideBehavior.Never),
      content = content,
    )
  }
}
