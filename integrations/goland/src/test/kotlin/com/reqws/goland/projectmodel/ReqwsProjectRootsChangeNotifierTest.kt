package com.reqws.goland.projectmodel

import com.intellij.openapi.components.service
import com.intellij.openapi.roots.ModuleRootEvent
import com.intellij.openapi.roots.ModuleRootListener
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import kotlinx.coroutines.runBlocking

class ReqwsProjectRootsChangeNotifierTest : BasePlatformTestCase() {
  override fun runInDispatchThread(): Boolean = false

  override fun isWriteActionRequired(): Boolean = false

  fun testPublishesAnOrdinaryGuardedRootsEventWithoutRequestingAPlatformRescan() = runBlocking {
    val guard = project.service<ReqwsProjectModelMutationGuard>()
    var eventCount = 0
    var workspaceModelOnly = true
    var guardActiveDuringEvent = false
    project.messageBus.connect(testRootDisposable).subscribe(
      ModuleRootListener.TOPIC,
      object : ModuleRootListener {
        override fun rootsChanged(event: ModuleRootEvent) {
          eventCount += 1
          workspaceModelOnly = event.isCausedByWorkspaceModelChangesOnly
          guardActiveDuringEvent = guard.isActive
        }
      },
    )

    val notified = PlatformReqwsProjectRootsChangeNotifier(project).notifyRootsChanged()

    assertTrue(notified)
    assertEquals(1, eventCount)
    assertFalse(workspaceModelOnly)
    assertTrue(guardActiveDuringEvent)
    assertFalse(guard.isActive)
  }

  fun testFinalMutationGatePreventsTheRootsEvent() = runBlocking {
    var eventCount = 0
    project.messageBus.connect(testRootDisposable).subscribe(
      ModuleRootListener.TOPIC,
      object : ModuleRootListener {
        override fun rootsChanged(event: ModuleRootEvent) {
          eventCount += 1
        }
      },
    )

    val notified = PlatformReqwsProjectRootsChangeNotifier(
      project = project,
      canNotify = { false },
    ).notifyRootsChanged()

    assertFalse(notified)
    assertEquals(0, eventCount)
    assertFalse(project.service<ReqwsProjectModelMutationGuard>().isActive)
  }
}
