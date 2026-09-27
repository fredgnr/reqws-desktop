package com.reqws.goland.ui.compose

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import com.reqws.goland.ReqwsBundle
import com.reqws.goland.ui.state.ReqwsUiState
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.component.Text
import androidx.compose.ui.unit.dp

@Composable
internal fun ReqwsSummary(state: ReqwsUiState) {
  ReqwsCard("reqws.summary", spacing = 4.dp) {
    ReqwsLiteralText(state.workspaceName.orEmpty(), "reqws.workspace",
      description = "${ReqwsBundle.message("field.workspace")} ${state.workspaceName.orEmpty()}".trim(),
      maxLines = 1, style = reqwsTitleTextStyle())
    ReqwsLiteralText(state.featureBranch.orEmpty(), "reqws.branch",
      description = "${ReqwsBundle.message("field.branch")} ${state.featureBranch.orEmpty()}".trim(),
      maxLines = 1, style = reqwsSmallTextStyle(), color = JewelTheme.globalColors.text.info)
    Text(ReqwsBundle.message("summary.loadedRepositories", state.loadedRepositoryCount),
      modifier = Modifier.testTag("reqws.loadedCount"), style = reqwsSmallTextStyle(),
      color = JewelTheme.globalColors.text.info)
  }
}
