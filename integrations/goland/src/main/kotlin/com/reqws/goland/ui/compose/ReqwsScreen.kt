package com.reqws.goland.ui.compose

import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import com.reqws.goland.ReqwsBundle
import com.reqws.goland.ui.state.ReqwsUiAction
import com.reqws.goland.ui.state.ReqwsUiState
import org.jetbrains.jewel.ui.component.DefaultButton
import org.jetbrains.jewel.ui.component.Text

/** S1 host entry; S2 fills out this same production screen. */
@Composable
internal fun ReqwsScreen(state: ReqwsUiState, onAction: (ReqwsUiAction) -> Unit) {
  Column {
    Text(ReqwsBundle.message(state.statusKey), modifier = Modifier.testTag("reqws.status"))
    DefaultButton(
      onClick = { onAction(ReqwsUiAction.SyncNow) },
      enabled = state.syncEnabled,
      modifier = Modifier.testTag("reqws.sync"),
    ) { Text(ReqwsBundle.message("action.syncNow")) }
  }
}
