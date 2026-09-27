package com.reqws.goland.ui.compose

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import com.reqws.goland.ReqwsBundle
import com.reqws.goland.ui.presentation.formatDetailsText
import com.reqws.goland.ui.state.ReqwsUiState
import org.jetbrains.jewel.ui.component.Text

@Composable
internal fun ReqwsSummary(state: ReqwsUiState) {
  ReqwsCard("reqws.summary") {
    ReqwsLiteralText(
      "${ReqwsBundle.message("field.workspace")} ${state.workspaceName.orEmpty()}", "reqws.workspace",
      tooltipEnabled = !state.workspaceName.isNullOrEmpty(),
    )
    ReqwsLiteralText(
      "${ReqwsBundle.message("field.branch")} ${state.featureBranch.orEmpty()}", "reqws.branch",
      tooltipEnabled = !state.featureBranch.isNullOrEmpty(),
    )
    ReqwsStatus(ReqwsBundle.message(state.statusKey), state.statusTone, "reqws.status")
    Text(ReqwsBundle.message("summary.loadedRepositories", state.loadedRepositoryCount),
      modifier = Modifier.testTag("reqws.loadedCount"))
    formatDetailsText(state)?.let {
      ReqwsLiteralText(it, "reqws.diagnostics", maxLines = 4)
    }
  }
}
