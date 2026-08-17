package com.reqws.goland.ui

import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.content.ContentFactory
import com.reqws.goland.project.ReqwsProjectDetector
import com.reqws.goland.project.ReqwsProjectService

internal class ReqwsToolWindowFactory : ToolWindowFactory {
  override suspend fun isApplicableAsync(project: Project): Boolean =
    ReqwsProjectDetector.projectRoot(project) != null

  override fun shouldBeAvailable(project: Project): Boolean =
    ReqwsProjectDetector.detect(project) != null

  override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
    val service = project.service<ReqwsProjectService>()
    // postStartupActivity is not guaranteed to run again when a plugin is dynamically enabled
    // in an already-open project. This idempotent entry point keeps the Tool Window recoverable.
    service.refresh()
    if (!project.isDisposed && !toolWindow.isDisposed) {
      toolWindow.setAvailable(ReqwsToolWindowViewModel.from(service.state).visible)
    }
    val panel = ReqwsToolWindowPanel(
      project = project,
      service = service,
    )
    val content = ContentFactory.getInstance().createContent(panel, null, false)
    content.setDisposer(panel)
    toolWindow.contentManager.addContent(content)
  }
}
