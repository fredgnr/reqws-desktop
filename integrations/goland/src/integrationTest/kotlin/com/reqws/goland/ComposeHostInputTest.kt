package com.reqws.goland

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
import java.awt.event.KeyEvent
import com.intellij.driver.sdk.ui.components.common.ideFrame
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
      try {
        for (dark in listOf(false, true)) {
          withContext(OnDispatcher.EDT) {
            manager.setAutodetect(false)
            manager.setCurrentUIThemeLookAndFeel(requireNotNull(if (dark) manager.getDefaultDarkLaf() else manager.getDefaultLightLaf()))
            manager.updateUI()
          }
          waitFor("real IDE theme applied", 30.seconds) { manager.getCurrentUIThemeLookAndFeel().isDark() == dark }
          focusContent()
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
      } finally {
        withContext(OnDispatcher.EDT) {
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
      try {
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
        node("reqws.copyDiagnostics").strictClick()
        waitFor("reloaded content accepts actual input", 10.seconds) { node("reqws.copyFeedback").present() }
        fixture.select(listOf("repo-a", "repo-b"))
        host.assertProjection(this, fixture)
        fixture.assertDiskPreserved()
        record("dynamic-reload", "unloaded content-disposed loaded empty-restored actual-click full-restored")
      } finally {
        setPluginEnabled(true)
      }
    }
  }

  private fun Driver.setPluginEnabled(enabled: Boolean) {
    openPluginsSettings()
    val page = ideFrame().pluginsSettingsPage().openInstalledTab().searchForPlugin("ReqWS")
    val checkbox = page.listPluginComponent("ReqWS").enabledCheckBox
    if (enabled) checkbox.check() else checkbox.uncheck()
    ideFrame().clickOkBtnAndCloseDialog()
  }

  private fun Driver.focusContent() {
    withContext(OnDispatcher.EDT) { utility<ComposeLifecycleAppIcon>().getInstance().requestFocus() }
    openToolWindow("ReqWS")
    waitFor("production Compose actions are present", 30.seconds) { node("reqws.sync").present() }
    waitFor("dedicated fixture owns the focused frame", 10.seconds) {
      cast(ideFrame().component, RemoteCaptureFrame::class).isActive()
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
    record("screenshot", screenshot.toString())
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
}
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
