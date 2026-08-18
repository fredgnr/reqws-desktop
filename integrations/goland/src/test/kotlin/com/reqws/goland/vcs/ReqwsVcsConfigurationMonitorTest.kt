package com.reqws.goland.vcs

import com.intellij.openapi.components.service
import com.intellij.openapi.vcs.ProjectLevelVcsManager
import com.intellij.openapi.vcs.VcsDirectoryMapping
import com.intellij.openapi.vcs.VcsRootSettings
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.util.ArrayDeque
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import org.jdom.Element

class ReqwsVcsConfigurationMonitorTest : BasePlatformTestCase() {
  fun testPublishesExternalConfigurationEventsAndAllowsListenerRemoval() {
    val monitor = project.service<ReqwsVcsConfigurationMonitor>()
    var changes = 0
    val registration = monitor.addExternalChangeListener { changes += 1 }

    publishConfigurationChanged()
    assertEquals(1, changes)

    registration.close()
    publishConfigurationChanged()
    assertEquals(1, changes)
  }

  fun testSuppressesTheMatchingSynchronousPluginWriteEvent() {
    val monitor = project.service<ReqwsVcsConfigurationMonitor>()
    var externalChanges = 0
    val registration = monitor.addExternalChangeListener { externalChanges += 1 }
    val current = ProjectLevelVcsManager.getInstance(project).getDirectoryMappings().toList()

    monitor.runPluginWrite(current) {
      publishConfigurationChanged()
    }
    assertEquals(0, externalChanges)

    // The first matching event after the synchronous callback is the one delayed self-event the
    // marker intentionally absorbs. The allowance is one-shot; a later same-list event is external.
    publishConfigurationChanged()
    assertEquals(0, externalChanges)
    publishConfigurationChanged()
    assertEquals(1, externalChanges)
    registration.close()
  }

  fun testWaitsForTwoStableFullSnapshotsBeforeReportingQuiescence() {
    val samples = ArrayDeque(
      listOf(
        VersionedVcsMappings(0, emptyList()),
        VersionedVcsMappings(1, emptyList()),
        VersionedVcsMappings(1, emptyList()),
        VersionedVcsMappings(1, emptyList()),
      ),
    )

    val result = ReqwsVcsConfigurationMonitor.awaitQuiescentSnapshot(
      snapshot = samples::removeFirst,
      pause = {},
      maxSamples = 3,
      requiredStableSamples = 2,
    )

    assertTrue(result.quiescent)
    assertEquals(1, result.revision)
    assertTrue(samples.isEmpty())
  }

  fun testReportsContinuousRevisionChurnAsNonQuiescent() {
    val samples = ArrayDeque(
      listOf(
        VersionedVcsMappings(0, emptyList()),
        VersionedVcsMappings(1, emptyList()),
        VersionedVcsMappings(2, emptyList()),
        VersionedVcsMappings(3, emptyList()),
      ),
    )

    val result = ReqwsVcsConfigurationMonitor.awaitQuiescentSnapshot(
      snapshot = samples::removeFirst,
      pause = {},
      maxSamples = 3,
      requiredStableSamples = 2,
    )

    assertFalse(result.quiescent)
    assertEquals(3, result.revision)
  }

  fun testLateLowerRevisionCannotReplaceNewerExternalFullSnapshot() {
    val target = AtomicReference<ExternalVcsMappings?>(null)
    val lowReady = CountDownLatch(1)
    val allowLowRecord = CountDownLatch(1)
    val lowFinished = CountDownLatch(1)
    val lowRecorded = AtomicBoolean(true)
    val low = ExternalVcsMappings(
      revision = 1,
      mappings = listOf(VcsDirectoryMapping("/tmp/reqws-low", "Git")),
    )
    val rootSettings = MonitorTestRootSettings("newer-user-settings")
    val high = ExternalVcsMappings(
      revision = 2,
      mappings = listOf(
        VcsDirectoryMapping("/tmp/reqws-high", "Perforce", rootSettings),
      ),
    )
    val lowWriter = Thread({
      lowReady.countDown()
      assertTrue(allowLowRecord.await(5, TimeUnit.SECONDS))
      lowRecorded.set(
        ReqwsVcsConfigurationMonitor.recordNewerExternalSnapshot(target, low),
      )
      lowFinished.countDown()
    }, "low-revision-vcs-event").apply { isDaemon = true }
    lowWriter.start()
    assertTrue(lowReady.await(5, TimeUnit.SECONDS))

    assertTrue(ReqwsVcsConfigurationMonitor.recordNewerExternalSnapshot(target, high))
    allowLowRecord.countDown()
    assertTrue(lowFinished.await(5, TimeUnit.SECONDS))

    assertFalse(lowRecorded.get())
    assertEquals(2L, target.get()?.revision)
    assertSame(rootSettings, target.get()?.mappings?.single()?.rootSettings)
  }

  private fun publishConfigurationChanged() {
    project.messageBus
      .syncPublisher(ProjectLevelVcsManager.VCS_CONFIGURATION_CHANGED)
      .directoryMappingChanged()
  }
}

private data class MonitorTestRootSettings(private val id: String) : VcsRootSettings {
  override fun readExternal(element: Element) = Unit

  override fun writeExternal(element: Element) = Unit
}
