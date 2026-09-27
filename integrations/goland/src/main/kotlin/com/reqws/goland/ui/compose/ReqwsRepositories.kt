package com.reqws.goland.ui.compose

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.selectable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import com.reqws.goland.ReqwsBundle
import com.reqws.goland.ui.state.ReqwsRepositoryUiState
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.component.Text

@Composable
internal fun ReqwsRepositories(repositories: List<ReqwsRepositoryUiState>) {
  var selectedId by remember { mutableStateOf<String?>(null) }
  val ids = repositories.map { it.catalogRepositoryId }
  LaunchedEffect(ids) { if (selectedId !in ids) selectedId = null }
  ReqwsCard("reqws.repositories") {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
      Text(ReqwsBundle.message("section.repositories"), modifier = Modifier.weight(1f))
      Text(repositories.size.toString(), modifier = Modifier.testTag("reqws.repositoryCount"))
    }
    if (repositories.isEmpty()) {
      Text(ReqwsBundle.message("message.noRepositories"), modifier = Modifier.testTag("reqws.empty"))
    } else {
      LazyColumn(
        modifier = Modifier.fillMaxWidth().heightIn(max = 320.dp).testTag("reqws.repositoryList"),
        state = rememberLazyListState(),
        verticalArrangement = Arrangement.spacedBy(4.dp),
      ) {
        items(repositories, key = { it.catalogRepositoryId }) { repository ->
          val selected = selectedId == repository.catalogRepositoryId
          val status = ReqwsBundle.message(repository.statusKey)
          val detail = repository.statusDetailKey?.let { ReqwsBundle.message(it) }
          val full = listOfNotNull(repository.name, status, detail).joinToString("\n")
          val tag = "reqws.repository.${repository.catalogRepositoryId}"
          Column(
            Modifier.fillMaxWidth()
              .background(if (selected) JewelTheme.globalColors.outlines.focused.copy(alpha = .12f)
                else JewelTheme.globalColors.panelBackground)
              .selectable(selected, role = Role.Button, onClick = { selectedId = repository.catalogRepositoryId })
              .semantics(mergeDescendants = true) { contentDescription = full; stateDescription = status }
              .testTag(tag).padding(8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
          ) {
            ReqwsLiteralText(repository.name, "$tag.name", maxLines = 1)
            ReqwsStatus(status, repository.statusTone, "$tag.status")
            if (detail != null) ReqwsLiteralText(detail, "$tag.detail")
          }
        }
      }
    }
  }
}
