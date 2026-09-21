package com.reqws.goland

import com.intellij.ide.starter.project.NoProject
import com.intellij.ide.starter.runner.Starter
import kotlin.time.Duration.Companion.minutes

/** Interactive account/permission preparation only; never a passing integration test. */
object LocalIdeAuthorization {
  @JvmStatic
  fun main(args: Array<String>) {
    val environment = LocalIdeEnvironment()
    environment.prepareHost()
    val context = environment.configure(Starter.newContext(
      "reqws-authorization", environment.testCase(NoProject),
    ))
    // No project and no ReqWS candidate installed. The user uses the normal account UI and quits.
    // No startup playback script may turn a missing license into a false unattended success.
    environment.recordLaunchRequested()
    val result = context.runIDE(runTimeout = 45.minutes, useStartupScript = false)
    check(result.exitCode == 0 && result.failureError == null) { "Authorization preparation did not exit normally" }
    environment.checkIdeErrors()
    environment.recordPreparationClosed()
  }
}
