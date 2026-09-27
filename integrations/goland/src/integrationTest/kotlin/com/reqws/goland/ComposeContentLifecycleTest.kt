package com.reqws.goland

import com.intellij.driver.client.Remote
import com.intellij.driver.client.service
import com.intellij.driver.client.utility
import com.intellij.driver.model.OnDispatcher
import com.intellij.driver.sdk.Project
import com.intellij.driver.sdk.openToolWindow
import com.intellij.driver.sdk.singleProject
import com.intellij.driver.sdk.ui.components.common.ideFrame
import com.intellij.driver.sdk.waitFor
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.nio.file.Files
import java.nio.file.StandardOpenOption
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ComposeContentLifecycleTest {
  private val host = IdeScenarioHost()

  @BeforeAll fun prepareEnvironment() = host.prepare()

  @Test fun contentLifecycleIsIndependentOfProjectSynchronization() {
    val fixture = WorkspaceFixture(host.root)
    val context = host.context("compose-content-lifecycle", fixture.shell).apply {
      isolateComposePluginState(host.root)
      applyVMOptionsPatch { addSystemProperty("reqws.sync.trace", true) }
    }
    host.withIde(context) {
      val reqws = service<ReqwsRemoteService>(singleProject())
      val windows = service<ComposeLifecycleToolWindowManager>(singleProject())
      waitFor("ReqWS Tool Window is registered without creating its Content", 30.seconds) { windows.getToolWindow("ReqWS") != null }
      val window = requireNotNull(windows.getToolWindow("ReqWS"))
      host.assertProjection(this, fixture, inspectReqwsContent = false)
      withContext(OnDispatcher.EDT) {
        assertTrue(window.getContentManagerIfCreated()?.getContentCount() in listOf(null, 0))
      }
      for (selected in listOf(listOf("repo-a"), emptyList(), listOf("repo-a", "repo-b"))) {
        fixture.select(selected)
        host.assertProjection(this, fixture, inspectReqwsContent = false)
        withContext(OnDispatcher.EDT) {
          assertTrue(window.getContentManagerIfCreated()?.getContentCount() in listOf(null, 0))
        }
      }
      withContext(OnDispatcher.EDT) { utility<ComposeLifecycleAppIcon>().getInstance().requestFocus() }
      openToolWindow("ReqWS")
      waitFor("production Compose content", 30.seconds) { ideFrame().x { byAttribute("testtag", "reqws.status") }.present() }
      repeat(20) { cycle ->
        val owner = withContext(OnDispatcher.EDT) {
          assertEquals(1, window.getContentManager().getContentCount())
          val owner = window.getContentManager().getContents().single().getDisposer()
          assertFalse(owner.isDisposed())
          new(ComposeLifecycleFactory::class).createToolWindowContent(singleProject(), window)
          assertEquals(1, window.getContentManager().getContentCount())
          window.hide(null)
          assertFalse(owner.isDisposed())
          owner
        }
        openToolWindow("ReqWS")
        waitFor("exactly one composition after show", 10.seconds) {
          ideFrame().xx { byAttribute("testtag", "reqws.screen") }.list().size == 1
        }
        withContext(OnDispatcher.EDT) {
          window.getContentManager().removeAllContents(true)
          assertTrue(owner.isDisposed())
          assertEquals(0, window.getContentManager().getContentCount())
        }
        val oldState = owner.getPresenter().getState()
        val oldLoaded = oldState.getValue().getLoadedRepositoryCount()
        fixture.select(if (cycle % 2 == 0) emptyList() else listOf("repo-a", "repo-b"))
        waitFor("project service survives Content disposal", 2.minutes) {
          reqws.getState().getSnapshot()?.getLoading()?.getProject()?.getRevision() == fixture.revision &&
            reqws.getState().getValidatedProjectionDigest() != null
        }
        assertEquals(oldLoaded, oldState.getValue().getLoadedRepositoryCount(), "disposed presenter cannot receive project updates")
        withContext(OnDispatcher.EDT) {
          new(ComposeLifecycleFactory::class).createToolWindowContent(singleProject(), window)
          assertFalse(window.getContentManager().getContents().single().getDisposer().isDisposed())
        }
        openToolWindow("ReqWS")
        waitFor("one recreated production composition", 10.seconds) {
          ideFrame().xx { byAttribute("testtag", "reqws.screen") }.list().size == 1
        }
        Files.writeString(host.root.resolve("compose-content-cycles.tsv"), "$cycle\t1\t0\t1\t$oldLoaded\n",
          StandardOpenOption.CREATE, StandardOpenOption.APPEND)
      }
      fixture.select(emptyList())
      host.assertProjection(this, fixture, inspectReqwsContent = false)
      fixture.assertDiskPreserved()
    }
    // The exact same fixture and candidate start in a fresh process with an empty model.
    host.withIde(context) {
      host.assertProjection(this, fixture, inspectReqwsContent = false)
      fixture.select(listOf("repo-a", "repo-b"))
      host.assertProjection(this, fixture, inspectReqwsContent = false)
      openToolWindow("ReqWS")
      waitFor("cold process recreates its own production content", 30.seconds) {
        ideFrame().xx { byAttribute("testtag", "reqws.screen") }.list().size == 1
      }
      fixture.assertDiskPreserved()
    }
  }
}

@Remote("com.intellij.openapi.wm.ToolWindowManager")
interface ComposeLifecycleToolWindowManager { fun getToolWindow(id: String): ComposeLifecycleToolWindow? }
@Remote("com.intellij.openapi.wm.ToolWindow")
interface ComposeLifecycleToolWindow {
  fun isVisible(): Boolean
  fun getContentManagerIfCreated(): ComposeLifecycleContentManager?
  fun getContentManager(): ComposeLifecycleContentManager
  fun hide(runnable: Runnable?)
}
@Remote("com.intellij.ui.content.ContentManager")
interface ComposeLifecycleContentManager {
  fun getContentCount(): Int
  fun removeAllContents(dispose: Boolean)
  fun getContents(): Array<ComposeLifecycleContent>
}
@Remote("com.reqws.goland.ui.ReqwsToolWindowFactory", plugin = "com.reqws.workspace")
interface ComposeLifecycleFactory { fun createToolWindowContent(project: Project, window: ComposeLifecycleToolWindow) }
@Remote("com.intellij.ui.content.Content")
interface ComposeLifecycleContent {
  fun getDisposer(): ComposeLifecycleOwner
}
@Remote("com.reqws.goland.ui.platform.ReqwsContentSession", plugin = "com.reqws.workspace")
interface ComposeLifecycleOwner {
  fun isDisposed(): Boolean
  fun getPresenter(): ComposeLifecyclePresenter
}
@Remote("com.reqws.goland.ui.presentation.ReqwsPresenter", plugin = "com.reqws.workspace")
interface ComposeLifecyclePresenter { fun getState(): ComposeLifecycleStateFlow }
@Remote("kotlinx.coroutines.flow.StateFlow")
interface ComposeLifecycleStateFlow { fun getValue(): ComposeLifecycleUiState }
@Remote("com.reqws.goland.ui.state.ReqwsUiState", plugin = "com.reqws.workspace")
interface ComposeLifecycleUiState { fun getLoadedRepositoryCount(): Int }
@Remote("com.intellij.ui.AppIcon")
interface ComposeLifecycleAppIcon {
  fun getInstance(): ComposeLifecycleAppIcon
  fun requestFocus()
}
