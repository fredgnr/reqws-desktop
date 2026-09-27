package com.reqws.goland

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import com.intellij.driver.sdk.ui.ui
import java.nio.file.StandardCopyOption
import com.intellij.driver.client.Driver
import com.intellij.driver.client.Remote
import com.intellij.driver.client.service
import com.intellij.driver.client.utility
import com.intellij.driver.model.OnDispatcher
import com.intellij.driver.sdk.VirtualFile
import com.intellij.driver.sdk.isPluginLoaded
import com.intellij.driver.sdk.openToolWindow
import com.intellij.driver.sdk.singleProject
import com.intellij.driver.sdk.ui.boundsOnScreen
import com.intellij.driver.sdk.ui.getClipboardText
import com.intellij.driver.sdk.ui.copyToClipboard
import java.awt.Point
import java.awt.Rectangle
import java.awt.event.KeyEvent
import com.intellij.driver.sdk.WaitForException
import com.intellij.driver.sdk.ui.components.UiComponent
import com.intellij.driver.sdk.ui.components.common.ideFrame
import com.intellij.driver.sdk.ui.components.elements.balloon
import com.intellij.driver.sdk.ui.components.settings.clickOkBtnAndCloseDialog
import com.intellij.driver.sdk.ui.components.settings.openPluginsSettings
import com.intellij.driver.sdk.ui.components.settings.pluginsSettingsPage
import com.intellij.driver.sdk.waitFor
import com.intellij.ide.starter.ide.IDETestContext
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/** Test-side public Driver/SDK adapters only. No command endpoint enters the candidate. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ComposeHostInputTest {
  private val host = IdeScenarioHost()

  @BeforeAll fun prepareEnvironment() = host.prepare()

  @Test fun productionActionsThemeAndScaleUseRealInput() {
    val fixture = WorkspaceFixture(host.root)
    val context = host.context("compose-real-input", fixture.shell).apply {
      isolateComposePluginState(host.root)
      applyVMOptionsPatch { addSystemProperty("reqws.sync.trace", true) }
    }
    host.withIde(context) {
      host.assertProjection(this, fixture)
      focusContent()
      capture("legacy-layout")
      val logRoot = Path.of(requireNotNull(utility<LocalIdeSystemProperties>().getProperty("idea.log.path"))).toRealPath()
      check(logRoot.startsWith(host.root))
      val log = logRoot.resolve("idea.log")
      fun manualReads() = Files.readAllLines(log).count { "event=READ_START" in it && "trigger=MANUAL" in it }
      var before = manualReads()
      node("reqws.sync").strictClick()
      waitFor("pointer click caused a manual domain read", 30.seconds) { manualReads() > before }
      waitFor("manual pointer synchronization finished", 30.seconds) {
        service<ReqwsRemoteService>(singleProject()).getState().getLifecycle().name() in setOf("SYNCHRONIZED", "DEGRADED")
      }
      dismissNotifications()
      node("reqws.copyDiagnostics").strictClick()
      focused("reqws.copyDiagnostics")
      recordFocus("before-reverse-tab")
      node("reqws.copyDiagnostics").keyboard {
        pressing(KeyEvent.VK_SHIFT) {
          recordFocus("shift-down")
          tab()
          recordFocus("tab-sent")
        }
      }
      recordFocus("shift-up")
      focused("reqws.openManifest")
      node("reqws.openManifest").keyboard { pressing(KeyEvent.VK_SHIFT) { tab() } }
      focused("reqws.sync")
      before = manualReads()
      node("reqws.sync").keyboard { space() }
      waitFor("Space caused another manual domain read", 30.seconds) { manualReads() > before }
      waitFor("manual keyboard synchronization finished", 30.seconds) {
        service<ReqwsRemoteService>(singleProject()).getState().getLifecycle().name() in setOf("SYNCHRONIZED", "DEGRADED")
      }
      dismissNotifications()
      node("reqws.copyDiagnostics").strictClick()
      focused("reqws.copyDiagnostics")
      recordFocus("before-reverse-tab")
      node("reqws.copyDiagnostics").keyboard {
        pressing(KeyEvent.VK_SHIFT) {
          recordFocus("shift-down")
          tab()
          recordFocus("tab-sent")
        }
      }
      recordFocus("shift-up")
      focused("reqws.openManifest")
      node("reqws.openManifest").keyboard { enter() }
      waitFor("Enter opened the exact fixture manifest", 30.seconds) {
        service<ComposeHostEditors>(singleProject()).getOpenFiles().any {
          it.getPath() == fixture.root.resolve(".reqws/workspace.json").toString()
        }
      }
      focusContent()
      dismissNotifications()
      node("reqws.copyDiagnostics").strictClick()
      waitFor("pointer copy has visible feedback and actual clipboard diagnostics", 10.seconds) {
        node("reqws.copyFeedback").present() && getClipboardText().toString().contains("strategy=loaded-roots-v1")
      }
      val clipboard = getClipboardText().toString()
      assertTrue(clipboard.contains("repositoryCount=2"))
      assertTrue(clipboard.contains("vcsMode=READ_ONLY_MANUAL"))
      val manager = utility<ComposeHostLafManager>().getInstance()
      val settings = utility<ComposeHostUISettings>().getInstance()
      val originalTheme = manager.getCurrentUIThemeLookAndFeel()
      val autodetect = manager.getAutodetect()
      val originalScale = settings.getCurrentIdeScale()
      val originalFontOverride = settings.getOverrideLafFonts()
      val originalFontSize = settings.getFontSize2D()
      try {
        for (dark in listOf(false, true)) {
          withContext(OnDispatcher.EDT) {
            manager.setAutodetect(false)
            manager.setCurrentUIThemeLookAndFeel(requireNotNull(if (dark) manager.getDefaultDarkLaf() else manager.getDefaultLightLaf()))
            manager.updateUI()
          }
          waitFor("real IDE theme applied", 30.seconds) { manager.getCurrentUIThemeLookAndFeel().isDark() == dark }
          focusContent()
          dismissNotifications()
          node("reqws.copyDiagnostics").strictClick()
          focused("reqws.copyDiagnostics")
          for (scale in listOf(1f, 1.25f)) {
            withContext(OnDispatcher.EDT) {
              settings.setCurrentIdeScale(scale)
              settings.fireUISettingsChanged()
            }
            waitFor("real IDE scale applied", 30.seconds) { settings.getCurrentIdeScale() == scale }
            // Re-query the real semantics after host theme/scale recomposition.
            focused("reqws.copyDiagnostics")
            val sentinel = "reqws-compose-keyboard-$dark-$scale"
            copyToClipboard(sentinel)
            assertEquals(sentinel, getClipboardText().toString())
            node("reqws.copyDiagnostics").keyboard { space() }
            waitFor("fresh copy works after theme and scale changes", 10.seconds) {
              node("reqws.copyFeedback").present() && getClipboardText().toString() == clipboard
            }
            val bounds = node("reqws.screen").boundsOnScreen
            for (tag in listOf("reqws.sync", "reqws.openManifest", "reqws.copyDiagnostics")) {
              assertTrue(bounds.contains(node(tag).boundsOnScreen), "$tag remains inside the rendered content")
            }
            capture("theme-${if (dark) "dark" else "light"}-scale-${if (scale == 1f) "100" else "125"}")
            record("theme", "dark=$dark scale=$scale focus=copyDiagnostics actions=3")
          }
        }
        withContext(OnDispatcher.EDT) {
          settings.setCurrentIdeScale(1f)
          settings.fireUISettingsChanged()
        }
        waitFor("baseline 100 percent row layout", 10.seconds) {
          node("reqws.repository.repo-a").boundsOnScreen.height == 40
        }
        val originalRow = node("reqws.repository.repo-a").boundsOnScreen
        val originalText = node("reqws.workspace").boundsOnScreen
        val originalLabelSize = utility<ComposeHostUIManager>().getFont("Label.font").getSize2D()
        checkNarrowStyles(fixture, manager, largeFont = false)
        withContext(OnDispatcher.EDT) {
          settings.setOverrideLafFonts(true)
          settings.setFontSize2D(originalLabelSize * 2f)
          settings.fireUISettingsChanged()
          manager.updateUI()
        }
        waitFor("actual host UI font increased independently of IDE scale", 30.seconds) {
          settings.getCurrentIdeScale() == 1f &&
            utility<ComposeHostUIManager>().getFont("Label.font").getSize2D() > originalLabelSize * 1.5f &&
            node("reqws.workspace").boundsOnScreen.height > originalText.height
        }
        val enlargedRow = node("reqws.repository.repo-a").boundsOnScreen
        val enlargedText = node("reqws.workspace").boundsOnScreen
        record("font-sizing", "scale=1 baseFont=$originalLabelSize baseRow=${originalRow.height} baseText=${originalText.height} enlargedFont=${utility<ComposeHostUIManager>().getFont("Label.font").getSize2D()} enlargedRow=${enlargedRow.height} enlargedText=${enlargedText.height}")
        capture("font-200")
        assertTrue(enlargedRow.height > originalRow.height, "repository row must grow with the actual IDE font")
        checkNarrowStyles(fixture, manager, largeFont = true)
      } finally {
        withContext(OnDispatcher.EDT) {
          settings.setOverrideLafFonts(originalFontOverride)
          settings.setFontSize2D(originalFontSize)
          settings.setCurrentIdeScale(originalScale)
          settings.fireUISettingsChanged()
          manager.setCurrentUIThemeLookAndFeel(originalTheme)
          manager.setAutodetect(autodetect)
          manager.updateUI()
        }
      }
      record("input", "pointer-sync keyboard-sync keyboard-open pointer-copy keyboard-copy")
      fixture.assertDiskPreserved()
    }
  }

  @Test fun settingsDisableAndEnableReleaseAndRecreateProductionContent() {
    val fixture = WorkspaceFixture(host.root)
    val context = host.context("compose-dynamic-reload", fixture.shell).apply {
      isolateComposePluginState(host.root)
      applyVMOptionsPatch { addSystemProperty("reqws.sync.trace", true) }
    }
    host.withIde(context) {
      host.assertProjection(this, fixture)
      focusContent()
      // Dynamic unload clears Disposer history for ordinary Disposable objects.
      // Platform CheckedDisposable markers retain their own permanent disposed bit.
      val disposalMarkers = withContext(OnDispatcher.EDT) {
        val content = service<ComposeLifecycleToolWindowManager>(singleProject()).getToolWindow("ReqWS")!!
          .getContentManager().getContents().single()
        val disposer = utility<ComposeHostDisposer>()
        listOf(disposer.newCheckedDisposable(content), disposer.newCheckedDisposable(content.getDisposer()))
      }
      val reloadManager = utility<ComposeHostLafManager>().getInstance()
      val reloadOriginalTheme = reloadManager.getCurrentUIThemeLookAndFeel()
      val reloadAutodetect = reloadManager.getAutodetect()
      // The Islands editor scheme is temporarily unregistered by the IDE's
      // global plugin reload. Use the built-in light theme for this one scenario.
      try {
        withContext(OnDispatcher.EDT) {
          reloadManager.setAutodetect(false)
          reloadManager.setCurrentUIThemeLookAndFeel(requireNotNull(reloadManager.getDefaultLightLaf()))
          reloadManager.updateUI()
        }
        setPluginEnabled(false)
        waitFor("plugin dynamically unloaded without restarting the IDE", 1.minutes) { !isPluginLoaded("com.reqws.workspace") }
        waitFor("old Content and session are disposed after unload", 30.seconds) { disposalMarkers.all { it.isDisposed() } }
        assertFalse(node("reqws.screen").present())
        fixture.select(emptyList())
        setPluginEnabled(true)
        waitFor("plugin dynamically loaded in the same IDE", 1.minutes) { isPluginLoaded("com.reqws.workspace") }
        host.assertProjection(this, fixture)
        focusContent()
        assertEquals(1, ideFrame().xx { byAttribute("testtag", "reqws.screen") }.list().size)
        dismissNotifications()
        node("reqws.copyDiagnostics").strictClick()
        waitFor("reloaded content accepts actual input", 10.seconds) { node("reqws.copyFeedback").present() }
        fixture.select(listOf("repo-a", "repo-b"))
        host.assertProjection(this, fixture)
        fixture.assertDiskPreserved()
        record("dynamic-reload", "unloaded content-disposed loaded empty-restored actual-click full-restored")
      } finally {
        try {
          setPluginEnabled(true)
        } finally {
          withContext(OnDispatcher.EDT) {
            reloadManager.setCurrentUIThemeLookAndFeel(reloadOriginalTheme)
            reloadManager.setAutodetect(reloadAutodetect)
            reloadManager.updateUI()
          }
        }
      }
    }
  }

  private fun Driver.checkNarrowStyles(fixture: WorkspaceFixture, manager: ComposeHostLafManager, largeFont: Boolean) {
    val window = cast(requireNotNull(service<ComposeLifecycleToolWindowManager>(singleProject()).getToolWindow("ReqWS")), ComposeHostToolWindowEx::class)
    val originalWidth = node("reqws.screen").boundsOnScreen.width
    val manifestPath = fixture.root.resolve(".reqws/workspace.json")
    val originalManifest = Files.readAllBytes(manifestPath)
    val mapper = ObjectMapper()
    val fullDiagnostic = "VCS_CONFIGURATION_MISMATCH · The Git Root configuration for loaded repositories needs review. " +
      "Configure it manually in Settings → Version Control → Directory Mappings, then check again."
    val size = if (largeFont) "large" else "base"
    try {
      for (dark in listOf(false, true)) {
        withContext(OnDispatcher.EDT) {
          manager.setCurrentUIThemeLookAndFeel(requireNotNull(if (dark) manager.getDefaultDarkLaf() else manager.getDefaultLightLaf()))
          manager.updateUI()
        }
        focusContent()
        val delta = 280 - node("reqws.screen").boundsOnScreen.width
        withContext(OnDispatcher.EDT) { window.stretchWidth(delta) }
        waitFor("actual narrow Tool Window", 10.seconds) { node("reqws.screen").boundsOnScreen.width in 275..285 }
        val theme = if (dark) "dark" else "light"
        val screen = node("reqws.screen").boundsOnScreen
        for (tag in listOf("reqws.sync", "reqws.openManifest", "reqws.copyDiagnostics")) {
          assertTrue(screen.contains(node(tag).boundsOnScreen), "$tag remains visible with $size font at narrow width")
        }
        val notice = node("reqws.diagnosticNotice").boundsOnScreen
        val font = utility<ComposeHostUIManager>().getFont("Label.font").getSize2D()
        assertTrue(notice.height <= (font + 2f) * 3 + 20, "diagnostic surface is bounded to three actual font lines")
        assertTrue(notice.maxY <= node("reqws.sync").boundsOnScreen.y, "diagnostics do not overlap the primary action")
        assertTrue(ideFrame().x { and(byAttribute("testtag", "reqws.diagnostics"), byAttribute("contentdescription", fullDiagnostic)) }.present())
        capture("font-$size-$theme-narrow")
        node("reqws.copyDiagnostics").strictClick()
        focused("reqws.copyDiagnostics")
        node("reqws.diagnostics").moveMouse()
        waitFor("actual tooltip retains the complete diagnostic", 10.seconds) {
          ui.x { and(byAttribute("testtag", "reqws.diagnostics.tooltip"), byAttribute("text", fullDiagnostic)) }.present()
        }
        capture("font-$size-$theme-tooltip")
        val tooltip = ui.x { byAttribute("testtag", "reqws.diagnostics.tooltip") }
        val tooltipBounds = stableBounds(tooltip)
        val tooltipRange = nativeVerticalScroll(tooltipBounds, fullDiagnostic)
        wheelToEnd(tooltip, tooltipRange, Point(tooltipBounds.centerX.toInt(), tooltipBounds.centerY.toInt()), "tooltip-$size-$theme")
        waitFor("tooltip remains open after real scrolling", 10.seconds) { tooltip.present() }
        capture("font-$size-$theme-tooltip-tail")
        node("reqws.copyDiagnostics").keyboard { escape() }
        waitFor("Escape dismisses the actual tooltip", 10.seconds) { !tooltip.present() }
        focused("reqws.copyDiagnostics")
        node("reqws.repositoryCount").moveMouse()
        node("reqws.diagnostics").moveMouse()
        waitFor("tooltip reopens before the outside-click check", 10.seconds) { tooltip.present() }
        val popupBounds = stableBounds(tooltip).apply { grow(24, 24) }
        val outsideTag = listOf("reqws.status", "reqws.workspace", "reqws.repositoryCount")
          .firstOrNull { !popupBounds.intersects(node(it).boundsOnScreen) }
        assertNotNull(outsideTag, "outside-click target must be outside the actual popup at this font size")
        record("style-outside-target", "font=$size dark=$dark tag=$outsideTag exclusionMargin=24 popup=$popupBounds target=${node(outsideTag!!).boundsOnScreen}")
        node(outsideTag).strictClick()
        waitFor("outside click dismisses the actual tooltip", 10.seconds) { !tooltip.present() }
        copyToClipboard("style-first-copy-sentinel")
        node("reqws.copyDiagnostics").strictClick()
        waitFor("first copy replaces the fresh sentinel", 10.seconds) {
          val copied = getClipboardText().toString()
          copied.startsWith("pluginVersion=") && copied.endsWith("errorField=")
        }
        val fullCopy = getClipboardText().toString()
        assertTrue(fullCopy.contains("vcsDiagnosticCode=VCS_CONFIGURATION_MISMATCH"))
        assertTrue(fullCopy.contains("repositoryCount=2"))
        assertTrue(fullCopy.endsWith("errorField="))
        assertEquals(listOf("pluginVersion", "ideBuild", "strategy", "lifecycle", "projectRoot", "manifestPath",
          "lastAppliedDigest", "candidateDigest", "repositoryCount", "missingRepositoryCount", "vcsMode",
          "configuredGitRootCount", "manualGitRootCount", "vcsDiagnosticCode", "vcsRepositoryStatuses",
          "vcsWorkspaceDiagnostics", "errorCode", "errorField"), fullCopy.lines().map { it.substringBefore('=') })
        copyToClipboard("style-copy-sentinel")
        node("reqws.copyDiagnostics").strictClick()
        waitFor("complete diagnostic copy is repeatable", 10.seconds) { getClipboardText().toString() == fullCopy }
        record("style-layout", "font=$size dark=$dark width=${screen.width} row=${node("reqws.repository.repo-a").boundsOnScreen.height} tooltipText=complete tooltipScroll=end tooltipTail=manual-review copy=full actions=3 escape=true outside=true")
        if (largeFont) {
          val manifest = mapper.readTree(originalManifest) as ObjectNode
          val repositories = manifest.withArray("repositories")
          for (index in 3..8) {
            val name = "repo-extra-$index"
            Files.createDirectories(fixture.root.resolve(name))
            repositories.addObject().put("catalogRepositoryId", name).put("name", name)
              .put("url", "https://example.test/$name.git").put("defaultBranch", "main").put("relativePath", name)
          }
          replaceStyleManifest(manifestPath, mapper.writeValueAsBytes(manifest))
          waitFor("eight real manifest repositories", 30.seconds) {
            ideFrame().x { and(byAttribute("testtag", "reqws.repositoryCount"), byAttribute("text", "8")) }.present()
          }
          assertTrue(node("reqws.repositoryScrollbar").present())
          capture("font-$size-$theme-long-list-before")
          // Compose semantics components have no AWT Window ancestor. Move by
          // the Driver's Compose-aware adapter, then wheel at the actual pointer.
          val body = node("reqws.body")
          val bodyBounds = stableBounds(body)
          wheelToEnd(body, nativeVerticalScroll(bodyBounds), Point(bodyBounds.x + 2, bodyBounds.y + 4), "body-$theme")
          val list = node("reqws.repositoryList")
          val listBounds = stableBounds(list)
          val firstY = node("reqws.repository.repo-a").boundsOnScreen.y
          wheelToEnd(list, nativeVerticalScroll(listBounds), Point(listBounds.centerX.toInt(), listBounds.centerY.toInt()), "list-$theme")
          waitFor("real wheel reveals the complete final row in the actual viewport", 10.seconds) {
            val last = node("reqws.repository.repo-extra-8")
            if (!last.present()) false else {
              val viewport = list.boundsOnScreen.intersection(node("reqws.body").boundsOnScreen)
              val lastBounds = last.boundsOnScreen
              val first = node("reqws.repository.repo-a")
              viewport.contains(lastBounds) && lastBounds.height >= 50 &&
                (!first.present() || first.boundsOnScreen.y < firstY)
            }
          }
          capture("font-$size-$theme-long-list")
          record("style-long-list", "dark=$dark repositories=8 last=repo-extra-8 wheel=true")
          manifest.putArray("repositories")
          fixture.select(emptyList())
          replaceStyleManifest(manifestPath, mapper.writeValueAsBytes(manifest))
          waitFor("actual empty manifest hint", 30.seconds) { node("reqws.empty").present() }
          val widen = 700 - node("reqws.screen").boundsOnScreen.width
          withContext(OnDispatcher.EDT) { window.stretchWidth(widen) }
          waitFor("wide empty hint reference", 10.seconds) { node("reqws.screen").boundsOnScreen.width >= 690 }
          val singleLineHeight = node("reqws.empty").boundsOnScreen.height
          val narrow = 280 - node("reqws.screen").boundsOnScreen.width
          withContext(OnDispatcher.EDT) { window.stretchWidth(narrow) }
          waitFor("narrow empty hint wraps beyond its single-line reference", 10.seconds) {
            node("reqws.screen").boundsOnScreen.width in 275..285 &&
              node("reqws.empty").boundsOnScreen.height > singleLineHeight * 1.5
          }
          val empty = node("reqws.empty").boundsOnScreen
          assertTrue(node("reqws.screen").boundsOnScreen.contains(empty))
          capture("font-$size-$theme-empty")
          record("style-empty", "dark=$dark width=${empty.width} height=${empty.height} singleLineHeight=$singleLineHeight wrapped=true")
          replaceStyleManifest(manifestPath, originalManifest)
          fixture.select(listOf("repo-a", "repo-b"))
          host.assertProjection(this, fixture)
          focusContent()
        }
      }
    } finally {
      if (!Files.readAllBytes(manifestPath).contentEquals(originalManifest)) {
        replaceStyleManifest(manifestPath, originalManifest)
        fixture.select(listOf("repo-a", "repo-b"))
      }
      val delta = originalWidth - node("reqws.screen").boundsOnScreen.width
      withContext(OnDispatcher.EDT) { window.stretchWidth(delta) }
    }
  }

  private fun Driver.stableBounds(component: UiComponent): Rectangle {
    var previous = Rectangle()
    var since = System.nanoTime()
    waitFor("nonzero stable native layout bounds", 10.seconds) {
      val next = component.boundsOnScreen
      if (next != previous) { previous = next; since = System.nanoTime() }
      next.width > 0 && next.height > 0 && System.nanoTime() - since >= 300_000_000
    }
    return Rectangle(previous)
  }

  private fun Driver.wheelToEnd(component: UiComponent, range: NativeScrollRange, point: Point, label: String) {
    val initial = range.current()
    val maximum = range.maximum()
    record("style-scroll-start", "$label current=$initial max=$maximum bounds=${range.bounds}")
    if (maximum <= initial + 0.5) return
    var direction = 0
    for (step in listOf(8, -8)) {
      component.robot.moveMouse(point)
      val actual = utility<NativeMouseInfo>().getPointerInfo().getLocation()
      check(range.bounds.contains(actual)) { "Actual pointer $actual is outside ${range.bounds}" }
      if (!label.startsWith("tooltip-")) node("reqws.copyDiagnostics").keyboard { escape() }
      component.robot.rotateMouseWheel(step)
      val moved = try {
        waitFor("real wheel changes $label scroll position", 2.seconds) { range.current() > initial + 0.5 }
        true
      } catch (_: WaitForException) { false }
      record("style-scroll-probe", "$label step=$step pointer=$actual current=${range.current()} max=${range.maximum()}")
      if (moved) { direction = step; break }
    }
    check(direction != 0) { "Neither real wheel direction moved $label" }
    repeat(12) {
      if (range.current() < range.maximum() - 0.5) {
        component.robot.moveMouse(point)
        if (!label.startsWith("tooltip-")) node("reqws.copyDiagnostics").keyboard { escape() }
        component.robot.rotateMouseWheel(direction)
      }
    }
    waitFor("real wheel reaches $label scroll end", 10.seconds) { range.current() >= range.maximum() - 0.5 }
    record("style-scroll-end", "$label current=${range.current()} max=${range.maximum()} direction=$direction")
  }

  private fun replaceStyleManifest(path: Path, bytes: ByteArray) {
    check(path.toRealPath().startsWith(host.root))
    val temporary = Files.createTempFile(path.parent, "native-style-", ".tmp")
    Files.write(temporary, bytes)
    Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
  }

  private fun Driver.setPluginEnabled(enabled: Boolean) {
    openPluginsSettings()
    val page = ideFrame().pluginsSettingsPage()
    page.installedTab.click()
    page.searchForPlugin("ReqWS")
    val row = page.listPluginComponent("ReqWS")
    // Marketplace detail loading is unrelated to the installed plugin checkbox.
    waitFor("installed ReqWS checkbox is available", 30.seconds) {
      row.present() && row.enabledCheckBox.present() && row.enabledCheckBox.isEnabled()
    }
    val checkbox = row.enabledCheckBox
    if (enabled) checkbox.check() else checkbox.uncheck()
    waitFor("installed ReqWS checkbox changed", 10.seconds) { checkbox.isSelected() == enabled }
    ideFrame().clickOkBtnAndCloseDialog()
  }

  private fun Driver.focusContent() {
    withContext(OnDispatcher.EDT) { utility<ComposeLifecycleAppIcon>().getInstance().requestFocus() }
    openToolWindow("ReqWS")
    waitFor("production Compose actions are present", 30.seconds) { node("reqws.sync").present() }
    waitFor("dedicated fixture owns the focused frame", 10.seconds) {
      cast(ideFrame().component, RemoteCaptureFrame::class).isActive()
    }
    dismissNotifications()
  }

  private fun Driver.dismissNotifications() {
    // Startup/synchronization notifications can arrive after the first quiet
    // frame. Wait for an unobstructed interval, without changing IDE settings.
    var quietSince = System.nanoTime()
    waitFor("native notification overlay has settled", 15.seconds) {
      val balloon = ideFrame().balloon("GOROOT is detected")
      if (balloon.present()) {
        balloon.moveMouse()
        val close = ideFrame().x { byAttribute("tooltiptext", "Close. ⌥click to close all notifications") }
        waitFor("native notification close is visible", 5.seconds) { close.present() }
        close.strictClick()
        waitFor("native notification balloon closed", 5.seconds) { !balloon.present() }
        quietSince = System.nanoTime()
        false
      } else {
        System.nanoTime() - quietSince >= 2_000_000_000L
      }
    }
  }

  private fun Driver.recordFocus(stage: String) {
    val focus = utility<ComposeHostFocusManager>().getCurrentKeyboardFocusManager()
    val owner = focus.getFocusOwner()?.toString().orEmpty().replace('\t', ' ').replace('\n', ' ')
    val permanent = focus.getPermanentFocusOwner()?.toString().orEmpty().replace('\t', ' ').replace('\n', ' ')
    val semantics = ideFrame().xx { byAttribute("focused", "true") }.list()
      .flatMap { it.getAllTexts().map { text -> text.text } }.joinToString(" | ")
    record("focus-$stage", "owner=$owner permanent=$permanent semantics=$semantics")
  }

  private fun Driver.node(tag: String) = ideFrame().x { byAttribute("testtag", tag) }
  private fun Driver.focused(tag: String) = waitFor("Compose focus is $tag", 10.seconds) {
    ideFrame().x { and(byAttribute("testtag", tag), byAttribute("focused", "true")) }.present()
  }

  private fun Driver.capture(name: String) {
    val screenshot = Path.of(requireNotNull(utility<ComposeHostScreenCapture>().takeFullScreenshot("compose-$name")))
    check(screenshot.toRealPath().startsWith(host.root) && Files.size(screenshot) > 0)
    record(if (name == "legacy-layout") "layout-screenshot" else if (name.startsWith("font-")) "style-screenshot" else "screenshot", screenshot.toString())
  }

  private fun record(event: String, detail: String) {
    Files.writeString(host.root.resolve("compose-input.tsv"), "$event\t$detail\n",
      StandardOpenOption.CREATE, StandardOpenOption.APPEND)
  }
}

@Remote("com.intellij.openapi.fileEditor.FileEditorManager")
interface ComposeHostEditors { fun getOpenFiles(): Array<VirtualFile> }
@Remote("com.intellij.ide.ui.LafManager")
interface ComposeHostLafManager {
  fun getInstance(): ComposeHostLafManager
  fun getCurrentUIThemeLookAndFeel(): ComposeHostTheme
  fun getDefaultLightLaf(): ComposeHostTheme?
  fun getDefaultDarkLaf(): ComposeHostTheme?
  fun setCurrentUIThemeLookAndFeel(theme: ComposeHostTheme)
  fun updateUI()
  fun getAutodetect(): Boolean
  fun setAutodetect(enabled: Boolean)
}
@Remote("com.intellij.ide.ui.laf.UIThemeLookAndFeelInfo")
interface ComposeHostTheme { fun isDark(): Boolean }
@Remote("com.intellij.ide.ui.UISettings")
interface ComposeHostUISettings {
  fun getInstance(): ComposeHostUISettings
  fun getCurrentIdeScale(): Float
  fun setCurrentIdeScale(scale: Float)
  fun fireUISettingsChanged()
  fun getOverrideLafFonts(): Boolean
  fun setOverrideLafFonts(enabled: Boolean)
  fun getFontSize2D(): Float
  fun setFontSize2D(size: Float)
}
@Remote("com.intellij.openapi.wm.ex.ToolWindowEx")
interface ComposeHostToolWindowEx { fun stretchWidth(delta: Int) }
@Remote("javax.swing.UIManager")
interface ComposeHostUIManager { fun getFont(key: Any): ComposeHostFont }
@Remote("java.awt.Font")
interface ComposeHostFont { fun getSize2D(): Float }
@Remote("com.intellij.openapi.util.Disposer")
interface ComposeHostDisposer {
  fun newCheckedDisposable(parent: ComposeLifecycleContent): ComposeHostCheckedDisposable
  fun newCheckedDisposable(parent: ComposeLifecycleOwner): ComposeHostCheckedDisposable
}
@Remote("com.intellij.openapi.util.CheckedDisposable")
interface ComposeHostCheckedDisposable { fun isDisposed(): Boolean }
@Remote("com.jetbrains.performancePlugin.commands.TakeScreenshotCommandKt", plugin = "com.jetbrains.performancePlugin")
interface ComposeHostScreenCapture { fun takeFullScreenshot(folder: String): String? }

/** Settings UI may persist a disabled plugin even if dynamic unloading fails.
 * Keep that setting in this run, never in the reusable authorization profile. */
internal fun IDETestContext.isolateComposePluginState(root: Path) {
  val disabled = pluginConfigurator.disabledPluginsPath.toAbsolutePath().normalize()
  check(disabled.startsWith(root) && !Files.isSymbolicLink(disabled))
  Files.createDirectories(disabled.parent)
  if (!Files.exists(disabled)) Files.writeString(disabled, "", StandardOpenOption.CREATE_NEW)
  check("com.reqws.workspace" !in Files.readAllLines(disabled))
  applyVMOptionsPatch { addSystemProperty("disabled.plugins.file.path", disabled) }
}

@Remote("java.awt.KeyboardFocusManager")
interface ComposeHostFocusManager {
  fun getCurrentKeyboardFocusManager(): ComposeHostFocusManager
  fun getFocusOwner(): ComposeHostFocusComponent?
  fun getPermanentFocusOwner(): ComposeHostFocusComponent?
}
@Remote("java.awt.Component")
interface ComposeHostFocusComponent { override fun toString(): String }
