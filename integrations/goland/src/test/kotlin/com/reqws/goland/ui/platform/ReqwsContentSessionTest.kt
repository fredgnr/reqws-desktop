package com.reqws.goland.ui.platform

import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.ui.content.ContentFactory
import com.reqws.goland.project.ReqwsLifecycleState
import com.reqws.goland.project.ReqwsProjectState
import com.reqws.goland.project.TerminalStatePublisher
import javax.swing.JPanel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

class ReqwsContentSessionTest : BasePlatformTestCase() {
  fun testContentDisposalReleasesSubscriptionAndScopeWithoutCancellingProjectWork() {
    val projectWork = SupervisorJob()
    val contentWork = SupervisorJob(projectWork)
    val session = ReqwsContentSession(CoroutineScope(contentWork + Dispatchers.Unconfined))
    val publisher = TerminalStatePublisher(ReqwsProjectState.INACTIVE) { it.lifecycle == ReqwsLifecycleState.DISPOSED }
    var closes = 0
    session.bind { listener ->
      val registration = publisher.addListener(listener)
      AutoCloseable { closes++; registration.close() }
    }
    val content = ContentFactory.getInstance().createContent(JPanel(), null, false)
    content.setDisposer(session)
    try {
      // Hiding a Content component does not dispose its owner.
      content.component.isVisible = false
      publisher.publish(ReqwsProjectState(ReqwsLifecycleState.READING))
      assertEquals(ReqwsLifecycleState.READING, session.presenter.state.value.lifecycle)
      Disposer.dispose(content)
      session.dispose()
      assertEquals(1, closes)
      assertTrue(contentWork.isCancelled)
      assertTrue(projectWork.isActive)
      publisher.publish(ReqwsProjectState(ReqwsLifecycleState.ERROR))
      assertEquals(ReqwsLifecycleState.READING, session.presenter.state.value.lifecycle)
      assertEquals(ReqwsLifecycleState.ERROR, publisher.state.lifecycle)
    } finally {
      Disposer.dispose(content)
      projectWork.cancel()
    }
  }

  fun testRecreatedContentGetsLatestProjectStateAndHasOneOwnedListener() {
    val publisher = TerminalStatePublisher(ReqwsProjectState.INACTIVE) { it.lifecycle == ReqwsLifecycleState.DISPOSED }
    var active = 0
    val projectWork = SupervisorJob()
    repeat(20) {
      val contentWork = SupervisorJob(projectWork)
      val session = ReqwsContentSession(CoroutineScope(contentWork + Dispatchers.Unconfined))
      val content = ContentFactory.getInstance().createContent(JPanel(), null, false)
      content.setDisposer(session)
      session.bind { listener ->
        active++
        val registration = publisher.addListener(listener)
        AutoCloseable { active--; registration.close() }
      }
      try {
        assertEquals(1, active)
        assertEquals(publisher.state.lifecycle, session.presenter.state.value.lifecycle)
        publisher.publish(ReqwsProjectState(ReqwsLifecycleState.ERROR))
      } finally {
        Disposer.dispose(content)
      }
      assertEquals(0, active)
      assertTrue(contentWork.isCompleted)
      assertTrue(projectWork.isActive)
      assertEquals(0, projectWork.children.count())
    }
    projectWork.cancel()
    assertTrue(projectWork.isCompleted)
  }
}
