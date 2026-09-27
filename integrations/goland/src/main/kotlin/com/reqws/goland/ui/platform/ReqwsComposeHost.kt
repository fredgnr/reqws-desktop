package com.reqws.goland.ui.platform

import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.intellij.openapi.application.ApplicationInfo
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.EDT
import com.intellij.openapi.components.service
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Key
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.wm.ToolWindow
import com.reqws.goland.ReqwsPlugin
import com.reqws.goland.diagnostics.ReqwsDiagnostics
import com.reqws.goland.project.ReqwsProjectDetector
import com.reqws.goland.project.ReqwsProjectService
import com.reqws.goland.ui.compose.ReqwsScreen
import com.reqws.goland.ui.state.ReqwsUiStateMapper
import java.awt.datatransfer.StringSelection
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jetbrains.jewel.bridge.addComposeTab

internal object ReqwsComposeHost {
  private val SESSION = Key.create<ReqwsContentSession>("reqws.compose.content")

  fun mount(project: Project, toolWindow: ToolWindow) {
    ApplicationManager.getApplication().assertIsDispatchThread()
    if (project.isDisposed || toolWindow.isDisposed) return
    val manager = toolWindow.contentManager
    if (manager.contents.any { it.getUserData(SESSION)?.isDisposed == false }) return
    val service = project.service<ReqwsProjectService>()
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val session = ReqwsContentSession(scope)
    val usable = { !session.isDisposed && !project.isDisposed && !toolWindow.isDisposed }
    val actions = ReqwsActionDispatcher(
      isUsable = usable,
      currentState = { service.state },
      sync = { service.refresh() },
      openManifest = {
        scope.launch(Dispatchers.IO) {
          if (!usable()) return@launch
          val root = ReqwsProjectDetector.projectRoot(project) ?: return@launch
          val file = LocalFileSystem.getInstance()
            .refreshAndFindFileByNioFile(ReqwsProjectDetector.manifestPath(root)) ?: return@launch
          withContext(Dispatchers.EDT) {
            if (usable() && ReqwsUiStateMapper.map(service.state).openManifestEnabled) {
              FileEditorManager.getInstance(project).openFile(file, true)
            }
          }
        }
      },
      copyDiagnostics = { state ->
        CopyPasteManager.getInstance().setContents(StringSelection(ReqwsDiagnostics.format(
          pluginVersion = ReqwsPlugin.VERSION,
          ideBuild = ApplicationInfo.getInstance().build.asString(),
          projectRoot = ReqwsProjectDetector.projectRoot(project),
          state = state,
        )))
      },
    )
    val before = manager.contents.toSet()
    try {
      toolWindow.addComposeTab(isCloseable = true, focusOnClickInside = true) {
        val state by session.presenter.state.collectAsState()
        ReqwsScreen(state) { action ->
          ApplicationManager.getApplication().assertIsDispatchThread()
          session.dispatch(action, actions)
        }
      }
      // The public helper returns Unit. Bind ownership to the exact newly added Content on EDT.
      val content = manager.contents.single { it !in before }
      content.putUserData(SESSION, session)
      content.setDisposer(session)
      session.bind(service::addListener)
    } catch (failure: Throwable) {
      session.dispose()
      manager.contents.filter { it !in before }.forEach { manager.removeContent(it, true) }
      throw failure
    }
  }
}
