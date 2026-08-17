package com.reqws.goland.project

import com.intellij.openapi.components.service
import com.intellij.testFramework.fixtures.BasePlatformTestCase

class ReqwsProjectServiceTest : BasePlatformTestCase() {
  fun testDisposePublishesTerminalStateAndMakesRefreshANoOp() {
    val service = project.service<ReqwsProjectService>()
    val observed = mutableListOf<ReqwsLifecycleState>()
    val handle = service.addListener { observed.add(it.lifecycle) }

    service.dispose()

    assertSame(ReqwsProjectState.DISPOSED, service.state)
    assertNull(service.refresh())
    assertEquals(ReqwsLifecycleState.INACTIVE, observed.first())
    assertEquals(ReqwsLifecycleState.DISPOSED, observed.last())
    handle.close()
  }
}
