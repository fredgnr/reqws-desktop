package com.reqws.goland.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asSkiaBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.unit.LayoutDirection
import com.reqws.goland.ui.presentation.formatDetailsText
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.jetbrains.jewel.foundation.theme.LocalTextStyle
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
  private val escapedOutsidePopup = mutableListOf<KeyEventType>()
  private val actions = mutableListOf<ReqwsUiAction>()
  private val direction = mutableStateOf(LayoutDirection.Ltr)
  private val fontSize = mutableStateOf(13.sp)
  private val widthOverride = mutableStateOf<Int?>(null)

  private fun sample(): ReqwsUiState = ReqwsUiStateMapper.map(
    ReqwsProjectState(ReqwsLifecycleState.ERROR, lastError = ReqwsProjectError("MANIFEST_INVALID_JSON")),
  ).copy(workspaceName = "Workspace 项目", featureBranch = "feature/compose", repositories = listOf(
    row("a"), row("b", status = "repository.notLoaded"),
  ), loadedRepositoryCount = 1)

  private fun row(id: String, name: String = "same", status: String = "repository.loaded") =
    ReqwsRepositoryUiState(id, name, status, ReqwsStatusTone.WARNING)

  private fun mount(width: Int = 320, height: Int = 740) {
    compose.setContent {
      CompositionLocalProvider(LocalDensity provides density.value, LocalLayoutDirection provides direction.value) {
        ReqwsTestTheme(dark.value) {
          CompositionLocalProvider(LocalTextStyle provides LocalTextStyle.current.copy(fontSize = fontSize.value)) {
            Box(Modifier.size((widthOverride.value ?: width).dp, height.dp).onKeyEvent {
              if (it.key == Key.Escape && it.type == KeyEventType.KeyDown) escapedOutsidePopup += it.type
              false
            }) { ReqwsScreen(state.value, actions::add) }
          }
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
    compose.runOnIdle { state.value = state.value.copy(repositories = state.value.repositories.drop(1).reversed()) }
    node("reqws.repositoryList").performScrollToKey("r199")
    node("reqws.repository.r199").assertIsSelected()
    compose.runOnIdle { state.value = state.value.copy(repositories = emptyList()) }
    node("reqws.empty").assertIsDisplayed()
    compose.runOnIdle { state.value = state.value.copy(repositories = listOf(row("r199"))) }
    node("reqws.repository.r199").assertIsNotSelected()
    assertActionsFit()
  }

  @Test fun longLiteralUnicodeValuesRetainCompleteTextAndBoundedLayout() {
    val value = "<html><b>  未受信任 & 👩🏽‍💻 e\u0301 </b>" + "长Long".repeat(160)
    state.value = state.value.copy(workspaceName = value, featureBranch = value,
      repositories = listOf(row("long", value, "repository.gitRootMissing")))
    mount(width = 240)
    node("reqws.workspace").assertTextEquals(value)
    node("reqws.branch").assertTextEquals(value)
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
    val value = "<html>  " + "👨‍👩‍👧‍👦👩🏽‍💻e\u0301🇨🇳汉字  ".repeat(45) + "END"
    compose.setContent { ReqwsTestTheme(false) { ReqwsFullText(value, "full") } }
    node("full").assertTextEquals(value)
    val layouts = mutableListOf<TextLayoutResult>()
    node("full").performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
    assertEquals(value, layouts.single().layoutInput.text.text)
    assertTrue(layouts.single().lineCount > 1)
    assertFalse(layouts.single().hasVisualOverflow)
    val boundaries = Regex("\\X").findAll(value).map { it.range.last + 1 }.toSet() + 0
    val layout = layouts.single()
    for (line in 0 until layout.lineCount) {
      assertTrue("line starts at a grapheme boundary", layout.getLineStart(line) in boundaries)
      assertTrue("line ends at a grapheme boundary", layout.getLineEnd(line) in boundaries)
    }
    val scroll = node("full").fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange]
    assertTrue(scroll.maxValue() > 0f)
    node("full").performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, scroll.maxValue()) }
    compose.waitForIdle()
    assertEquals(scroll.maxValue(), scroll.value(), 1f)
    assertTrue(node("full").fetchSemanticsNode().boundsInRoot.width <= 320f)
    assertTrue(node("full").fetchSemanticsNode().boundsInRoot.height <= 240f)
  }

  @Test fun hoverExposesTheActualProductionTooltip() {
    mount()
    node("reqws.workspace").performMouseInput { enter(center) }
    compose.waitUntil(5_000) { compose.onAllNodesWithTag("reqws.workspace.tooltip").fetchSemanticsNodes().isNotEmpty() }
    node("reqws.workspace.tooltip").assertTextEquals("Workspace 项目")
  }

  @Test fun fullTextPopupStaysOpenForPointerScrollingAndClosesAfterExit() {
    state.value = state.value.copy(workspaceName = "long tooltip ".repeat(100) + "END")
    mount()
    node("reqws.workspace").performMouseInput { enter(center) }
    compose.waitUntil(5_000) { compose.onAllNodesWithTag("reqws.workspace.tooltip").fetchSemanticsNodes().isNotEmpty() }
    node("reqws.workspace").performMouseInput { exit() }
    val tooltip = node("reqws.workspace.tooltip")
    tooltip.performMouseInput { enter(center); scroll(androidx.compose.ui.geometry.Offset(0f, 1000f)) }
    compose.waitForIdle()
    val range = tooltip.fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange]
    assertTrue("real pointer wheel scrolls the tooltip", range.value() > 0f)
    tooltip.assertTextEquals(state.value.workspaceName!!)
    tooltip.performMouseInput { exit() }
    compose.waitUntil(5_000) { compose.onAllNodesWithTag("reqws.workspace.tooltip").fetchSemanticsNodes().isEmpty() }
  }

  @Test fun focusedActionDismissesTooltipWithoutLosingFocusOrLeavingStaleEscapeHandlers() {
    mount()
    val copy = node("reqws.copyDiagnostics")
    copy.performMouseInput { click(center) }
    copy.assertIsFocused()
    node("reqws.workspace").performMouseInput { enter(center) }
    compose.waitUntil(5_000) { compose.onAllNodesWithTag("reqws.workspace.tooltip").fetchSemanticsNodes().isNotEmpty() }
    copy.performKeyInput { pressKey(Key.Escape) }
    compose.waitUntil(5_000) { compose.onAllNodesWithTag("reqws.workspace.tooltip").fetchSemanticsNodes().isEmpty() }
    copy.assertIsFocused()
    assertTrue(escapedOutsidePopup.isEmpty())
    copy.performKeyInput { pressKey(Key.Escape) }
    assertEquals(1, escapedOutsidePopup.size)
    node("reqws.workspace").performMouseInput { exit(); enter(center) }
    compose.waitUntil(5_000) { compose.onAllNodesWithTag("reqws.workspace.tooltip").fetchSemanticsNodes().isNotEmpty() }
    compose.runOnIdle { state.value = state.value.copy(workspaceName = "") }
    compose.waitUntil(5_000) { compose.onAllNodesWithTag("reqws.workspace.tooltip").fetchSemanticsNodes().isEmpty() }
    copy.performKeyInput { pressKey(Key.Escape) }
    assertEquals(2, escapedOutsidePopup.size)
    copy.assertIsFocused()
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
    assertTrue(node("reqws.repository.root").fetchSemanticsNode().config[SemanticsProperties.ContentDescription]
      .any { ReqwsBundle.message("repository.userRootCoverageDetail") in it })
    node("reqws.repository.git.status.text", true).assertTextEquals(ReqwsBundle.message("repository.gitStatusUnavailable"))
    assertActionsFit()
  }

  @Test fun summaryAndRepositoryCardsUseThemeBackgroundAndThemeChangesPreserveSelection() {
    mount()
    node("reqws.repository.b").performClick()
    val before = node("reqws.screen").captureToImage().toPixelMap()[0, 0]
    assertEquals(testLightBackground, before)
    for (tag in listOf("reqws.summary", "reqws.repositories")) {
      val pixels = node(tag).captureToImage().toPixelMap()
      assertTrue("$tag uses the light panel token", (0 until pixels.height step 4).any { y ->
        (0 until pixels.width step 4).count { x -> pixels[x, y] == testLightPanel } > pixels.width / 8
      })
    }
    screenshot("light")
    compose.runOnIdle { dark.value = true }
    node("reqws.repository.b").assertIsSelected()
    val after = node("reqws.screen").captureToImage().toPixelMap()[0, 0]
    assertEquals(testDarkBackground, after)
    assertNotEquals(before, after)
    assertActionsFit()
    for (tag in listOf("reqws.summary", "reqws.repositories")) {
      val pixels = node(tag).captureToImage().toPixelMap()
      assertTrue("$tag uses the dark panel token", (0 until pixels.height step 4).any { y ->
        (0 until pixels.width step 4).count { x -> pixels[x, y] == testDarkPanel } > pixels.width / 8
      })
    }
    screenshot("dark")
  }

  @Test fun fontAndDensityChangesKeepButtonsWithinTheScreen() {
    mount(width = 280, height = 640)
    compose.runOnIdle { density.value = Density(1.25f, 1.5f) }
    assertActionsFit()
    node("reqws.sync").assertTextContains(ReqwsBundle.message("action.syncNow"))
    screenshot("scaled")
    for (key in listOf("action.syncNow", "action.openManifest", "action.copyDiagnostics")) {
      val layouts = mutableListOf<TextLayoutResult>()
      compose.onNodeWithText(ReqwsBundle.message(key), useUnmergedTree = true)
        .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
      val result = layouts.single()
      // Link's paragraph may keep the offered width while its node shrink-wraps
      // the actual text. Check every rendered line, including the final character.
      assertFalse("$key keeps all lines", result.multiParagraph.didExceedMaxLines)
      assertEquals(result.layoutInput.text.length, result.getLineEnd(result.lineCount - 1))
      for (line in 0 until result.lineCount) {
        assertFalse(result.isLineEllipsized(line))
        assertTrue("$key keeps its complete width", result.getLineRight(line) <= result.size.width + 1f)
        assertTrue("$key keeps its complete height", result.getLineBottom(line) <= result.size.height + 1f)
      }
    }
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
    assertEquals(40f, one, 1f)
    node("reqws.repositoryScrollbar").assertDoesNotExist()
    compose.runOnIdle { state.value = state.value.copy(repositories = listOf(row("a"), row("b"))) }
    val two = node("reqws.repositoryList").fetchSemanticsNode().boundsInRoot.height
    assertTrue(two > one)
    compose.runOnIdle { state.value = state.value.copy(repositories = List(100) { row("r$it") }) }
    assertEquals(240f, node("reqws.repositoryList").fetchSemanticsNode().boundsInRoot.height, 1f)
    node("reqws.repositoryScrollbar").assertIsDisplayed()
    assertActionsFit()
  }

  @Test fun everyProductionRepositoryStatusRetainsCompleteSemanticsAtNarrowWidth() {
    state.value = state.value.copy(repositories = listOf(row("a")))
    mount(width = 240)
    listOf("loaded", "notLoaded", "missing", "projectContentUnavailable", "gitRootMissing", "gitRootConflict",
      "gitStatusUnavailable", "notGit", "userRootCoverage").forEach { key ->
      compose.runOnIdle { state.value = state.value.copy(repositories = listOf(row("a", status = "repository.$key"))) }
      val layouts = mutableListOf<TextLayoutResult>()
      node("reqws.repository.a.status.text", true).performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
      assertEquals(ReqwsBundle.message("repository.$key"), layouts.single().layoutInput.text.text)
      val status = node("reqws.repository.a.status.text", true).fetchSemanticsNode().boundsInRoot
      val row = node("reqws.repository.a").fetchSemanticsNode().boundsInRoot
      assertTrue(status.width > 0 && status.left >= row.left && status.right <= row.right)
      node("reqws.repository.a").assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription,
        ReqwsBundle.message("repository.$key")))
    }
  }

  @Test fun summaryStatusIsAddressableInMergedSemanticsForEveryLifecycle() {
    mount(width = 240)
    listOf("inactive", "reading", "safeModeBlocked", "synchronizing", "synchronized", "degraded", "error", "disposed").forEach { key ->
      compose.runOnIdle { state.value = state.value.copy(statusKey = "state.$key") }
      val expected = ReqwsBundle.message("state.$key")
      node("reqws.status").assertTextEquals(expected)
        .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, expected))
      val layouts = mutableListOf<TextLayoutResult>()
      node("reqws.status.text", true).performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
      assertFalse(layouts.single().hasVisualOverflow)
    }
  }


  @Test fun preservedErrorHasCompleteAccessibleTextTooltipAndSeparateLiveFeedback() {
    val error = "MANIFEST_INVALID_JSON <html> 👩🏽‍💻 e\u0301 " + "X".repeat(160)
    state.value = state.value.copy(errorCode = error, preservedSnapshot = true, diagnosticsCopied = true,
      errorDetailKey = "message.projectFileIndexNotConverged")
    mount(width = 240)
    val expected = requireNotNull(formatDetailsText(state.value))
    assertTrue(expected.contains(ReqwsBundle.message("message.preservedModel")))
    node("reqws.diagnostics").assertTextEquals(expected).assertContentDescriptionEquals(expected)
    node("reqws.copyFeedback").assert(SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, LiveRegionMode.Polite))
    node("reqws.diagnostics").performMouseInput { enter(center) }
    compose.waitUntil(5_000) { compose.onAllNodesWithTag("reqws.diagnostics.tooltip").fetchSemanticsNodes().isNotEmpty() }
    node("reqws.diagnostics.tooltip").assertTextEquals(expected)
    val diagnostics = node("reqws.diagnostics").fetchSemanticsNode().boundsInRoot
    val footer = node("reqws.actions").fetchSemanticsNode().boundsInRoot
    assertTrue(diagnostics.top >= footer.top)
    assertTrue(diagnostics.bottom <= node("reqws.sync").fetchSemanticsNode().boundsInRoot.top)
    assertActionsFit()
    compose.runOnIdle { state.value = state.value.copy(diagnosticsCopied = false, errorCode = "NEW_ERROR") }
    node("reqws.copyFeedback").assertDoesNotExist()
    node("reqws.diagnostics").assertTextEquals(requireNotNull(formatDetailsText(state.value)))
  }

  @Test fun userRootExplanationRemainsAccessibleInBothDirectionsAtNarrowWidths() {
    state.value = state.value.copy(repositories = listOf(row("root", "repo3", "repository.userRootCoverage")
      .copy(statusDetailKey = "repository.userRootCoverageDetail")))
    mount(width = 160)
    for (width in listOf(160, 240, 480)) for (layout in listOf(LayoutDirection.Ltr, LayoutDirection.Rtl)) {
      compose.runOnIdle { widthOverride.value = width; direction.value = layout }
      val row = node("reqws.repository.root")
      row.performScrollTo().assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button))
      val expected = listOf("repo3", ReqwsBundle.message("repository.userRootCoverage"),
        ReqwsBundle.message("repository.userRootCoverageDetail")).joinToString("\n")
      assertTrue(expected in row.fetchSemanticsNode().config[SemanticsProperties.ContentDescription])
      val bounds = row.fetchSemanticsNode().boundsInRoot
      for (tag in listOf("name", "status.text")) {
        val child = node("reqws.repository.root.$tag", true).fetchSemanticsNode().boundsInRoot
        assertTrue(child.left >= bounds.left && child.right <= bounds.right)
      }
      node("reqws.repository.root.name", true).assertTextEquals("repo3")
      row.performClick().assertIsSelected()
      assertActionsFit()
    }
  }

  @Test fun keyboardSkipsDisabledActionsAndPreservesFocusAcrossThemeChanges() {
    state.value = state.value.copy(openManifestEnabled = false)
    mount()
    node("reqws.sync").performSemanticsAction(SemanticsActions.RequestFocus) { it() }
    node("reqws.sync").performKeyInput { pressKey(Key.Tab) }
    node("reqws.copyDiagnostics").assertIsFocused()
    compose.runOnIdle { dark.value = true }
    node("reqws.copyDiagnostics").assertIsFocused().performKeyInput { pressKey(Key.Enter) }
    node("reqws.copyDiagnostics").performKeyInput { withKeyDown(Key.ShiftLeft) { pressKey(Key.Tab) } }
    node("reqws.sync").assertIsFocused()
    assertEquals(listOf(ReqwsUiAction.CopyDiagnostics), actions)
    node("reqws.repository.a").performSemanticsAction(SemanticsActions.RequestFocus) { it() }
    node("reqws.repository.a").performKeyInput { pressKey(Key.Spacebar) }
    node("reqws.repository.a").assertIsSelected()
  }


  @Test fun legacyVisualHierarchyKeepsStatusAboveCardsAndDiagnosticsAbovePrimaryAndLinks() {
    state.value = state.value.copy(workspaceName = "ReqWS automation", featureBranch = "fixture",
      lifecycle = ReqwsLifecycleState.DEGRADED, statusKey = "state.degraded", statusTone = ReqwsStatusTone.WARNING,
      loadedRepositoryCount = 2, errorCode = null, vcsDiagnosticCode = "VCS_CONFIGURATION_MISMATCH",
      statusDetailKey = "message.vcsManualConfigurationRequired", repositories = listOf(
        row("a", "repo-a").copy(statusTone = ReqwsStatusTone.SUCCESS),
        row("b", "repo-b").copy(statusTone = ReqwsStatusTone.SUCCESS)))
    mount(width = 432, height = 820)
    val status = node("reqws.status").fetchSemanticsNode().boundsInRoot
    val summary = node("reqws.summary").fetchSemanticsNode().boundsInRoot
    val repositories = node("reqws.repositories").fetchSemanticsNode().boundsInRoot
    assertTrue(status.bottom < summary.top)
    assertTrue(status.width < summary.width)
    assertTrue(summary.bottom < repositories.top)
    node("reqws.workspace").assertTextEquals("ReqWS automation")
      .assertContentDescriptionEquals("${ReqwsBundle.message("field.workspace")} ReqWS automation")
    node("reqws.branch").assertTextEquals("fixture")
    val row = node("reqws.repository.a").fetchSemanticsNode().boundsInRoot
    val name = node("reqws.repository.a.name", true).fetchSemanticsNode().boundsInRoot
    val rowStatus = node("reqws.repository.a.status", true).fetchSemanticsNode().boundsInRoot
    assertEquals(40f, row.height, 1f)
    assertTrue(name.right <= rowStatus.left)
    val sync = node("reqws.sync").fetchSemanticsNode().boundsInRoot
    val open = node("reqws.openManifest").fetchSemanticsNode().boundsInRoot
    val copy = node("reqws.copyDiagnostics").fetchSemanticsNode().boundsInRoot
    assertTrue(node("reqws.diagnostics").fetchSemanticsNode().boundsInRoot.bottom < sync.top)
    assertTrue(sync.height >= 36f)
    assertTrue(open.width < sync.width && copy.width < sync.width)
    assertTrue(sync.bottom < open.top && open.bottom < copy.top)
    assertEquals(sync.center.x, open.center.x, 1f)
    assertEquals(sync.center.x, copy.center.x, 1f)
    assertActionsFit()
    screenshot("legacy-layout")
    compose.runOnIdle { dark.value = true }
    screenshot("legacy-layout-dark")
  }

  @Test fun shortRepositoryNameLeavesRemainingWidthForLongStatus() {
    state.value = state.value.copy(repositories = listOf(row("root", "r", "repository.userRootCoverage")))
    mount(width = 432)
    val completeStatusWidth = node("reqws.repository.root.status", true).fetchSemanticsNode().boundsInRoot.width
    // Leave only the original body/row padding, gap and the short name around
    // the measured full status; a fixed two-thirds cap would truncate it here.
    compose.runOnIdle { widthOverride.value = (completeStatusWidth + 60f).toInt() }
    assertEquals(completeStatusWidth,
      node("reqws.repository.root.status", true).fetchSemanticsNode().boundsInRoot.width, 1f)
    compose.runOnIdle { state.value = state.value.copy(repositories = listOf(
      row("root", "VeryLongRepositoryName".repeat(20), "repository.userRootCoverage"))) }
    val row = node("reqws.repository.root").fetchSemanticsNode().boundsInRoot
    val name = node("reqws.repository.root.name", true).fetchSemanticsNode().boundsInRoot
    val status = node("reqws.repository.root.status", true).fetchSemanticsNode().boundsInRoot
    assertTrue("long names retain their own share", name.width >= (row.width - 28f) / 3f - 1f)
    assertTrue("only a long name reduces the status allocation", status.width < completeStatusWidth)
    assertTrue(name.right <= status.left)
  }

  @Test fun attentionDiagnosticsWrapWithinThreeLinesAndKeepCompleteText() {
    state.value = state.value.copy(errorCode = "VCS_CONFIGURATION_MISMATCH", preservedSnapshot = true,
      errorDetailKey = "message.vcsManualConfigurationRequired")
    mount(width = 240, height = 640)
    val complete = requireNotNull(formatDetailsText(state.value))
    for (isDark in listOf(false, true)) {
      compose.runOnIdle { dark.value = isDark }
      node("reqws.diagnosticNotice").assertIsDisplayed()
      node("reqws.diagnostics").assertTextEquals(complete).assertContentDescriptionEquals(complete)
      val layouts = mutableListOf<TextLayoutResult>()
      node("reqws.diagnostics").performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
      val layout = layouts.single()
      assertEquals(3, layout.lineCount)
      // The desktop paragraph reports truncation through didExceedMaxLines;
      // isLineEllipsized can be false even when the rendered third line ends in an ellipsis.
      assertTrue("long diagnostics are bounded", layout.multiParagraph.didExceedMaxLines)
      assertTrue(layout.getLineEnd(2) < complete.length)
      assertTrue("all three visible lines fit", layout.getLineBottom(2) <= layout.size.height + 1f)
      assertActionsFit()
    }
    screenshot("product-design-diagnostic-dark")
    compose.runOnIdle { density.value = Density(1f, 1.5f) }
    assertActionsFit()
    node("reqws.diagnostics").assertContentDescriptionEquals(complete)
    screenshot("product-design-diagnostic-scaled")
  }

  @Test fun routineDiagnosticsStayCompactWithoutAnAlertSurface() {
    state.value = state.value.copy(errorCode = null, errorDetailKey = null, preservedSnapshot = false,
      statusTone = ReqwsStatusTone.SUCCESS, statusKey = "state.synchronized", digest = "123456789abc")
    mount()
    node("reqws.diagnosticNotice").assertDoesNotExist()
    node("reqws.diagnostics").assertTextEquals(ReqwsBundle.message("message.currentDigest", "123456789abc"))
    val layouts = mutableListOf<TextLayoutResult>()
    node("reqws.diagnostics").performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
    assertEquals(1, layouts.single().lineCount)
    assertActionsFit()
  }

  @Test fun enlargedRepositoryRowsAndEmptyHintKeepTheirCompleteTextHeight() {
    state.value = state.value.copy(repositories = listOf(row("a", "repo-a")))
    mount(width = 240)
    compose.runOnIdle { density.value = Density(1f, 2f) }
    val rowBounds = node("reqws.repository.a").fetchSemanticsNode().boundsInRoot
    assertEquals(80f, rowBounds.height, 1f)
    for (tag in listOf("reqws.repository.a.name", "reqws.repository.a.status.text")) {
      val textBounds = node(tag, true).fetchSemanticsNode().boundsInRoot
      val layouts = mutableListOf<TextLayoutResult>()
      node(tag, true).performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
      assertTrue("$tag fits vertically", layouts.single().getLineBottom(0) <= textBounds.height + 1f)
      assertTrue(textBounds.top >= rowBounds.top && textBounds.bottom <= rowBounds.bottom)
    }
    compose.runOnIdle { state.value = state.value.copy(repositories = emptyList()) }
    val layouts = mutableListOf<TextLayoutResult>()
    node("reqws.empty").performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
    val layout = layouts.single()
    assertFalse(layout.multiParagraph.didExceedMaxLines)
    assertEquals(layout.layoutInput.text.length, layout.getLineEnd(layout.lineCount - 1))
    assertTrue(layout.getLineBottom(layout.lineCount - 1) <= layout.size.height + 1f)
    assertActionsFit()
    screenshot("product-design-empty-scaled")
  }

  @Test fun hostTextStyleGrowthAtConstantDensityExpandsRepositoryRows() {
    mount(width = 280)
    val normal = node("reqws.repository.a").fetchSemanticsNode().boundsInRoot.height
    compose.runOnIdle { fontSize.value = 26.sp }
    assertEquals(Density(1f, 1f), density.value)
    val enlarged = node("reqws.repository.a").fetchSemanticsNode().boundsInRoot
    assertTrue("host font growth increases row height", enlarged.height > normal)
    val layouts = mutableListOf<TextLayoutResult>()
    node("reqws.repository.a.name", true).performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
    assertFalse(layouts.single().multiParagraph.didExceedMaxLines)
    assertTrue(layouts.single().size.height <= enlarged.height - 10f)
    assertActionsFit()
    screenshot("actual-host-font-growth")
  }

  private fun screenshot(name: String) {
    val image = node("reqws.screen").captureToImage()
    val directory = Path.of(System.getProperty("reqws.compose.reports", "build/reports/compose-ui"), "screenshots")
    Files.createDirectories(directory)
    Image.makeFromBitmap(image.asSkiaBitmap()).use { skia ->
      skia.encodeToData()!!.use { data -> Files.write(directory.resolve("$name.png"), data.bytes) }
    }
  }
}
