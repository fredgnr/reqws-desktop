package com.reqws.goland.project

import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import com.intellij.openapi.util.Disposer
import com.reqws.goland.ui.ReqwsToolWindowAvailabilityController

internal class ReqwsStartupActivity : ProjectActivity {
  override suspend fun execute(project: Project) {
    // Start the lightweight fixed-path watcher even when the manifest is initially absent. This
    // lets an already-open project become a ReqWS project after Desktop atomically creates it.
    if (ReqwsProjectDetector.projectRoot(project) != null) {
      val service = project.service<ReqwsProjectService>()
      val availabilityController = ReqwsToolWindowAvailabilityController.forProject(project)
      Disposer.register(project, availabilityController)
      availabilityController.bind(service)
      service.refresh()
    }
  }
}
