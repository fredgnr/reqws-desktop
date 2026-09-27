package com.reqws.goland.ui.compose

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.ui.Alignment
import com.reqws.goland.ReqwsBundle
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.reqws.goland.ui.state.ReqwsUiAction
import com.reqws.goland.ui.state.ReqwsUiState
import org.jetbrains.jewel.foundation.theme.JewelTheme

/** Production content: immutable display data in, local UI state and typed events out. */
@Composable
internal fun ReqwsScreen(state: ReqwsUiState, onAction: (ReqwsUiAction) -> Unit) {
  Column(
    Modifier.fillMaxSize().background(JewelTheme.globalColors.toolwindowBackground)
      .testTag("reqws.screen"),
  ) {
    Column(
      Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(12.dp)
        .testTag("reqws.body"),
      verticalArrangement = Arrangement.Top,
    ) {
      ReqwsStatus(ReqwsBundle.message(state.statusKey), state.statusTone, "reqws.status",
        modifier = Modifier.align(Alignment.End), pill = true)
      Spacer(Modifier.height(8.dp))
      ReqwsSummary(state)
      Spacer(Modifier.height(12.dp))
      ReqwsRepositories(state.repositories)
    }
    ReqwsActions(state, onAction)
  }
}
