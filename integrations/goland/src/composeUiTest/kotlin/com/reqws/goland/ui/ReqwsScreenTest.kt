package com.reqws.goland.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asSkiaBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.reqws.goland.ReqwsBundle
import com.reqws.goland.project.ReqwsLifecycleState
import com.reqws.goland.project.ReqwsProjectError
import com.reqws.goland.project.ReqwsProjectState
import com.reqws.goland.ui.compose.ReqwsFullText
import com.reqws.goland.ui.compose.ReqwsScreen
import com.reqws.goland.ui.state.ReqwsRepositoryUiState
import com.reqws.goland.ui.state.ReqwsStatusTone
import com.reqws.goland.ui.state.ReqwsUiAction
import com.reqws.goland.ui.state.ReqwsUiState
import com.reqws.goland.ui.state.ReqwsUiStateMapper
import java.nio.file.Files
import java.nio.file.Path
import org.jetbrains.skia.Image
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class ReqwsScreenTest {
  @get:Rule val compose = createComposeRule()
  private val state = mutableStateOf(sample())
  private val dark = mutableStateOf(false)
  private val density = mutableStateOf(Density(1f, 1f))
  private val actions = mutableListOf<ReqwsUiAction>()

  private fun sample(): ReqwsUiState = ReqwsUiStateMapper.map(
    ReqwsProjectState(ReqwsLifecycleState.ERROR, lastError = ReqwsProjectError("MANIFEST_INVALID_JSON")),
  ).copy(workspaceName = "Workspace 项目", featureBranch = "feature/compose", repositories = listOf(
    row("a"), row("b", status = "repository.notLoaded"),
  ), loadedRepositoryCount = 1)

  private fun row(id: String, name: String = "same", status: String = "repository.loaded") =
    ReqwsRepositoryUiState(id, name, status, ReqwsStatusTone.WARNING)

  private fun mount(width: Int = 320, height: Int = 740) {
    compose.setContent {
      CompositionLocalProvider(LocalDensity provides density.value) {
        ReqwsTestTheme(dark.value) {
          Box(Modifier.size(width.dp, height.dp)) { ReqwsScreen(state.value, actions::add) }
        }
      }
    }
  }

  private fun node(tag: String, unmerged: Boolean = false) = compose.onNodeWithTag(tag, useUnmergedTree = unmerged)
  private fun assertActionsFit() {
    val screen = node("reqws.screen").fetchSemanticsNode().boundsInRoot
    val footer = node("reqws.actions").fetchSemanticsNode().boundsInRoot
    assertTrue(footer.top >= screen.top && footer.bottom <= screen.bottom)
    for (tag in listOf("reqws.sync", "reqws.openManifest", "reqws.copyDiagnostics")) {
      node(tag).assertIsDisplayed()
      val bounds = node(tag).fetchSemanticsNode().boundsInRoot
      assertTrue("$tag stays within screen", bounds.left >= screen.left && bounds.right <= screen.right)
      assertTrue(bounds.height > 0)
    }
  }

  @Test fun rendersAllActionsWithButtonRoleAndDispatchesTypedEvents() {
    mount()
    val pairs = listOf("reqws.sync" to ReqwsUiAction.SyncNow, "reqws.openManifest" to ReqwsUiAction.OpenManifest,
      "reqws.copyDiagnostics" to ReqwsUiAction.CopyDiagnostics)
    pairs.forEach { (tag, _) -> node(tag).assertIsEnabled().assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button))
      .performMouseInput { click() } }
    assertEquals(pairs.map { it.second }, actions)
    assertActionsFit()
  }

  @Test fun disabledActionsRejectPointerEventsAndExposeDisabledSemantics() {
    state.value = state.value.copy(syncEnabled = false, openManifestEnabled = false, copyDiagnosticsEnabled = false)
    mount()
    listOf("reqws.sync", "reqws.openManifest", "reqws.copyDiagnostics").forEach {
      node(it).assertIsNotEnabled().performMouseInput { click() }
    }
    assertTrue(actions.isEmpty())
  }

  @Test fun keyboardTabSpaceAndEnterActivateTheFocusedProductionButtons() {
    mount()
    node("reqws.sync").performSemanticsAction(SemanticsActions.RequestFocus) { it() }
    node("reqws.sync").assertIsFocused().performKeyInput { pressKey(Key.Spacebar); pressKey(Key.Tab) }
    node("reqws.openManifest").assertIsFocused().performKeyInput { pressKey(Key.Enter); pressKey(Key.Tab) }
    node("reqws.copyDiagnostics").assertIsFocused().performKeyInput { pressKey(Key.Spacebar) }
    assertEquals(listOf(ReqwsUiAction.SyncNow, ReqwsUiAction.OpenManifest, ReqwsUiAction.CopyDiagnostics), actions)
  }

  @Test fun sameNameRowsKeepSelectionByIdThroughReorderAndDeletion() {
    mount()
    node("reqws.repository.b").performClick().assertIsSelected()
    compose.runOnIdle { state.value = state.value.copy(repositories = state.value.repositories.reversed()) }
    node("reqws.repository.b").assertIsSelected()
    node("reqws.repository.a").assertIsNotSelected()
    compose.runOnIdle { state.value = state.value.copy(repositories = listOf(row("a"))) }
    node("reqws.repository.b").assertDoesNotExist()
    node("reqws.repository.a").assertIsNotSelected()
    compose.runOnIdle { state.value = state.value.copy(repositories = listOf(row("a"), row("b"))) }
    node("reqws.repository.b").assertIsNotSelected()
    assertTrue(actions.isEmpty())
  }

  @Test fun emptyListAndConfirmedLoadedCountAreIndependent() {
    state.value = state.value.copy(repositories = emptyList(), loadedRepositoryCount = 0)
    mount()
    node("reqws.empty").assertTextEquals(ReqwsBundle.message("message.noRepositories"))
    node("reqws.repositoryCount").assertTextEquals("0")
    node("reqws.loadedCount").assertTextEquals(ReqwsBundle.message("summary.loadedRepositories", 0))
    assertActionsFit()
  }

  @Test fun largeListScrollsToStableIdsWithoutInventingLoadedCount() {
    state.value = state.value.copy(repositories = List(200) { row("r$it", "仓库-$it") }, loadedRepositoryCount = 2)
    mount()
    node("reqws.repositoryCount").assertTextEquals("200")
    node("reqws.loadedCount").assertTextEquals(ReqwsBundle.message("summary.loadedRepositories", 2))
    node("reqws.repositoryList").performScrollToKey("r199")
    node("reqws.repository.r199").assertIsDisplayed().performClick().assertIsSelected()
    node("reqws.repositoryList").performScrollToKey("r0")
    node("reqws.repository.r0").assertIsNotSelected()
    assertActionsFit()
  }

  @Test fun longLiteralUnicodeValuesRetainCompleteTextAndBoundedLayout() {
    val value = "<html><b>  未受信任 & 👩🏽‍💻 e\u0301 </b>" + "长Long".repeat(160)
    state.value = state.value.copy(workspaceName = value, featureBranch = value,
      repositories = listOf(row("long", value, "repository.gitRootMissing")))
    mount(width = 240)
    node("reqws.workspace").assertTextEquals("${ReqwsBundle.message("field.workspace")} $value")
    node("reqws.branch").assertTextEquals("${ReqwsBundle.message("field.branch")} $value")
    node("reqws.repository.long.name", true).assertTextEquals(value)
    val text = mutableListOf<TextLayoutResult>()
    node("reqws.repository.long.name", true).performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(text) }
    assertEquals(value, text.single().layoutInput.text.text)
    assertTrue(text.single().hasVisualOverflow)
    val row = node("reqws.repository.long").fetchSemanticsNode()
    assertTrue(row.config[SemanticsProperties.ContentDescription].any { value in it })
    assertTrue(row.boundsInRoot.width <= 240f)
    assertActionsFit()
    screenshot("narrow-unicode")
  }

  @Test fun fullTextTooltipContentWrapsWithoutDroppingSpacesMarkupOrGraphemes() {
    val value = "<html>  👨‍👩‍👧‍👦e\u0301🇨🇳" + "x".repeat(600)
    compose.setContent { ReqwsTestTheme(false) { ReqwsFullText(value, "full") } }
    node("full").assertTextEquals(value)
    val layouts = mutableListOf<TextLayoutResult>()
    node("full").performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
    assertEquals(value, layouts.single().layoutInput.text.text)
    assertTrue(layouts.single().lineCount > 1)
    assertFalse(layouts.single().hasVisualOverflow)
    assertTrue(node("full").fetchSemanticsNode().boundsInRoot.width <= 320f)
    assertTrue(node("full").fetchSemanticsNode().boundsInRoot.height <= 240f)
  }

  @Test fun hoverExposesTheActualProductionTooltip() {
    mount()
    node("reqws.workspace").performMouseInput { enter(center) }
    compose.waitUntil(5_000) { compose.onAllNodesWithTag("reqws.workspace.tooltip").fetchSemanticsNodes().isNotEmpty() }
    node("reqws.workspace.tooltip").assertTextEquals("${ReqwsBundle.message("field.workspace")} Workspace 项目")
  }

  @Test fun errorAndRepeatedCopyFeedbackRemainSeparateAndUpdatesClearTheFeedback() {
    state.value = state.value.copy(diagnosticsCopied = true, errorDetailKey = "message.projectFileIndexNotConverged")
    mount(width = 240)
    repeat(3) { compose.runOnIdle { state.value = state.value.copy(diagnosticsCopied = true) } }
    node("reqws.diagnostics").assertTextContains("MANIFEST_INVALID_JSON", substring = true)
    node("reqws.copyFeedback").assertTextEquals(ReqwsBundle.message("message.diagnosticsCopied"))
    compose.onAllNodesWithTag("reqws.copyFeedback").assertCountEquals(1)
    assertActionsFit()
    compose.runOnIdle { state.value = state.value.copy(diagnosticsCopied = false, errorCode = "NEW_ERROR") }
    node("reqws.copyFeedback").assertDoesNotExist()
    node("reqws.diagnostics").assertTextContains("NEW_ERROR", substring = true)
  }

  @Test fun longestStatusesAndUserRootExplanationRemainAvailableInNarrowRows() {
    state.value = state.value.copy(repositories = listOf(
      row("root", "repo3", "repository.userRootCoverage").copy(statusDetailKey = "repository.userRootCoverageDetail"),
      row("git", "git", "repository.gitStatusUnavailable")))
    mount(width = 240)
    node("reqws.repository.root").assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription,
      ReqwsBundle.message("repository.userRootCoverage")))
    node("reqws.repository.root.detail", true).assertTextEquals(ReqwsBundle.message("repository.userRootCoverageDetail"))
    node("reqws.repository.git.status", true).assertTextEquals(ReqwsBundle.message("repository.gitStatusUnavailable"))
    assertActionsFit()
  }

  @Test fun summaryAndRepositoryCardsUseThemeBackgroundAndThemeChangesPreserveSelection() {
    mount()
    node("reqws.repository.b").performClick()
    val before = node("reqws.screen").captureToImage().toPixelMap()[0, 0]
    assertEquals(testLightBackground, before)
    screenshot("light")
    compose.runOnIdle { dark.value = true }
    node("reqws.repository.b").assertIsSelected()
    val after = node("reqws.screen").captureToImage().toPixelMap()[0, 0]
    assertEquals(testDarkBackground, after)
    assertNotEquals(before, after)
    assertActionsFit()
    screenshot("dark")
  }

  @Test fun fontAndDensityChangesKeepButtonsWithinTheScreen() {
    mount(width = 280, height = 640)
    compose.runOnIdle { density.value = Density(1.25f, 1.5f) }
    assertActionsFit()
    node("reqws.sync").assertTextContains(ReqwsBundle.message("action.syncNow"))
    screenshot("scaled")
  }

  @Test fun repositoryHeadingAndCountHaveSeparateNonOverlappingBounds() {
    mount()
    val count = node("reqws.repositoryCount").fetchSemanticsNode().boundsInRoot
    val heading = compose.onNodeWithText(ReqwsBundle.message("section.repositories")).fetchSemanticsNode().boundsInRoot
    assertTrue(heading.right <= count.left)
    assertTrue(count.right <= node("reqws.repositories").fetchSemanticsNode().boundsInRoot.right)
  }

  @Test fun emptyManifestValuesDoNotShowRedundantTooltips() {
    state.value = state.value.copy(workspaceName = null, featureBranch = null)
    mount()
    node("reqws.workspace").performMouseInput { enter(center) }
    compose.mainClock.advanceTimeBy(1_000)
    node("reqws.workspace.tooltip").assertDoesNotExist()
    node("reqws.branch").performMouseInput { moveTo(center) }
    compose.mainClock.advanceTimeBy(1_000)
    node("reqws.branch.tooltip").assertDoesNotExist()
  }

  @Test fun repositoryViewportGrowsWithShortContentAndCapsLongLists() {
    state.value = state.value.copy(repositories = listOf(row("a")))
    mount()
    val one = node("reqws.repositoryList").fetchSemanticsNode().boundsInRoot.height
    compose.runOnIdle { state.value = state.value.copy(repositories = listOf(row("a"), row("b"))) }
    val two = node("reqws.repositoryList").fetchSemanticsNode().boundsInRoot.height
    assertTrue(two > one)
    compose.runOnIdle { state.value = state.value.copy(repositories = List(100) { row("r$it") }) }
    assertTrue(node("reqws.repositoryList").fetchSemanticsNode().boundsInRoot.height <= 320f)
    assertActionsFit()
  }

  @Test fun everyProductionRepositoryStatusRetainsVisibleTextAtNarrowWidth() {
    state.value = state.value.copy(repositories = listOf(row("a")))
    mount(width = 240)
    listOf("loaded", "notLoaded", "missing", "projectContentUnavailable", "gitRootMissing", "gitRootConflict",
      "gitStatusUnavailable", "notGit", "userRootCoverage").forEach { key ->
      compose.runOnIdle { state.value = state.value.copy(repositories = listOf(row("a", status = "repository.$key"))) }
      val layouts = mutableListOf<TextLayoutResult>()
      node("reqws.repository.a.status", true).performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
      assertEquals(ReqwsBundle.message("repository.$key"), layouts.single().layoutInput.text.text)
      assertFalse("$key status remains visible", layouts.single().hasVisualOverflow)
    }
  }

  private fun screenshot(name: String) {
    val image = node("reqws.screen").captureToImage()
    val directory = Path.of("build/reports/compose-s2/screenshots")
    Files.createDirectories(directory)
    Image.makeFromBitmap(image.asSkiaBitmap()).use { skia ->
      skia.encodeToData()!!.use { data -> Files.write(directory.resolve("$name.png"), data.bytes) }
    }
  }
}
