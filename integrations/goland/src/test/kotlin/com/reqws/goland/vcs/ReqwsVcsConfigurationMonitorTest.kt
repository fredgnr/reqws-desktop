package com.reqws.goland.vcs

import com.intellij.openapi.components.service
import com.intellij.openapi.vcs.ProjectLevelVcsManager
import com.intellij.testFramework.fixtures.BasePlatformTestCase

class ReqwsVcsConfigurationMonitorTest : BasePlatformTestCase() {
  fun testPublishesConfigurationEventsAndAllowsListenerRemoval() {
    val monitor = project.service<ReqwsVcsConfigurationMonitor>()
    var changes = 0
    val registration = monitor.addExternalChangeListener { changes += 1 }

    publishConfigurationChanged()
    assertEquals(1, changes)

    registration.close()
    publishConfigurationChanged()
    assertEquals(1, changes)
  }

  fun testOneFailingListenerDoesNotBlockOtherListeners() {
    val monitor = project.service<ReqwsVcsConfigurationMonitor>()
    var changes = 0
    val failing = monitor.addExternalChangeListener { error("observer failed") }
    val healthy = monitor.addExternalChangeListener { changes += 1 }

    publishConfigurationChanged()

    assertEquals(1, changes)
    failing.close()
    healthy.close()
  }

  fun testDisposeClearsListenersAndRejectsLaterRegistrations() {
    val monitor = project.service<ReqwsVcsConfigurationMonitor>()
    var changes = 0
    monitor.addExternalChangeListener { changes += 1 }

    monitor.dispose()
    publishConfigurationChanged()
    monitor.addExternalChangeListener { changes += 1 }
    publishConfigurationChanged()

    assertEquals(0, changes)
  }

  private fun publishConfigurationChanged() {
    project.messageBus
      .syncPublisher(ProjectLevelVcsManager.VCS_CONFIGURATION_CHANGED)
      .directoryMappingChanged()
  }
}
