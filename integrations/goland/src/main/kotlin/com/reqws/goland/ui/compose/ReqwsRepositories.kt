package com.reqws.goland.ui.compose

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.selectable
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.reqws.goland.ReqwsBundle
import com.reqws.goland.ui.state.ReqwsRepositoryUiState
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.component.Tooltip
import org.jetbrains.jewel.ui.component.VerticalScrollbar
import org.jetbrains.jewel.ui.component.styling.LocalScrollbarStyle
import org.jetbrains.jewel.ui.component.styling.LocalSelectableLazyColumnStyle

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun ReqwsRepositories(repositories: List<ReqwsRepositoryUiState>) {
  var selectedId by remember { mutableStateOf<String?>(null) }
  val ids = repositories.map { it.catalogRepositoryId }
  LaunchedEffect(ids) { if (selectedId !in ids) selectedId = null }
  val colors = JewelTheme.globalColors
  val listColors = LocalSelectableLazyColumnStyle.current.simpleListItemStyle.colors
  ReqwsCard("reqws.repositories", padding = 0.dp, spacing = 0.dp) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
      horizontalArrangement = Arrangement.SpaceBetween) {
      Text(ReqwsBundle.message("section.repositories"), modifier = Modifier.weight(1f), fontWeight = FontWeight.Bold)
      Text(repositories.size.toString(), modifier = Modifier.testTag("reqws.repositoryCount"), color = colors.text.info)
    }
    Box(Modifier.fillMaxWidth().height(1.dp).background(colors.borders.normal))
    if (repositories.isEmpty()) {
      Box(Modifier.fillMaxWidth().height(40.dp), contentAlignment = Alignment.Center) {
        Text(ReqwsBundle.message("message.noRepositories"), modifier = Modifier.testTag("reqws.empty"), color = colors.text.info)
      }
    } else {
      val listState = rememberLazyListState()
      val scrollbarWidth = if (repositories.size > 6) LocalScrollbarStyle.current.scrollbarVisibility.trackThickness else 0.dp
      Box(Modifier.fillMaxWidth().height((40 * repositories.size.coerceAtMost(6)).dp)) {
        LazyColumn(
          modifier = Modifier.fillMaxSize().padding(end = scrollbarWidth).testTag("reqws.repositoryList"),
          state = listState,
        ) {
          itemsIndexed(repositories, key = { _, repository -> repository.catalogRepositoryId }) { index, repository ->
            val selected = selectedId == repository.catalogRepositoryId
            val status = ReqwsBundle.message(repository.statusKey)
            val detail = repository.statusDetailKey?.let { ReqwsBundle.message(it) }
            val full = listOfNotNull(repository.name, status, detail).joinToString("\n")
            val tag = "reqws.repository.${repository.catalogRepositoryId}"
            Tooltip(tooltip = { ReqwsFullText(full, "$tag.tooltip") }) {
              Box(Modifier.fillMaxWidth().height(40.dp)
                .background(if (selected) listColors.backgroundSelectedActive else colors.panelBackground)
                .selectable(selected, role = Role.Button, onClick = { selectedId = repository.catalogRepositoryId })
                .semantics(mergeDescendants = true) { contentDescription = full; stateDescription = status }
                .testTag(tag)) {
                BoxWithConstraints(Modifier.fillMaxSize().padding(horizontal = 10.dp, vertical = 5.dp)) {
                  val statusMaxWidth = (maxWidth - 8.dp) * (2f / 3f)
                  Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ReqwsLiteralText(repository.name, "$tag.name", modifier = Modifier.weight(1f),
                      maxLines = 1, tooltipEnabled = false,
                      color = if (selected) listColors.contentSelectedActive else colors.text.normal)
                    ReqwsStatus(status, repository.statusTone, "$tag.status",
                      modifier = Modifier.widthIn(max = statusMaxWidth), tooltipEnabled = false,
                      textColor = if (selected) listColors.contentSelectedActive else colors.text.normal)
                  }
                }
                if (index < repositories.lastIndex) {
                  Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(1.dp).background(colors.borders.normal))
                }
              }
            }
          }
        }
        if (repositories.size > 6) {
          VerticalScrollbar(listState, modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight()
            .testTag("reqws.repositoryScrollbar"))
        }
      }
    }
  }
}
