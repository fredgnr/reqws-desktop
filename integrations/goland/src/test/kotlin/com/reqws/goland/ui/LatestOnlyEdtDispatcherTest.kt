package com.reqws.goland.ui

import com.reqws.goland.project.ReqwsLifecycleState
import com.reqws.goland.project.ReqwsProjectState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LatestOnlyEdtDispatcherTest {
  @Test
  fun `older queued action cannot overwrite a newer action delivered on EDT`() {
    val edt = FakeEdt()
    val rendered = mutableListOf<String>()

    assertTrue(edt.dispatcher.submit { rendered += "older" })
    edt.isEventDispatchThread = true
    assertTrue(edt.dispatcher.submit { rendered += "newer" })
    edt.runQueued(0)

    assertEquals(listOf("newer"), rendered)
  }

  @Test
  fun `only newest queued action runs even when queued callbacks run out of order`() {
    val edt = FakeEdt()
    val rendered = mutableListOf<String>()

    assertTrue(edt.dispatcher.submit { rendered += "first" })
    assertTrue(edt.dispatcher.submit { rendered += "second" })
    edt.runQueued(1)
    edt.runQueued(0)

    assertEquals(listOf("second"), rendered)
  }

  @Test
  fun `terminal Tool Window state invalidates queued rendering and rejects later states`() {
    val edt = FakeEdt()
    val stateDispatcher = ReqwsToolWindowStateDispatcher(edt.dispatcher)
    val rendered = mutableListOf<ReqwsLifecycleState>()
    val isUsable = { true }
    val render = { state: ReqwsProjectState -> rendered += state.lifecycle }

    assertTrue(
      stateDispatcher.accept(
        ReqwsProjectState(lifecycle = ReqwsLifecycleState.READING),
        isUsable,
        render,
      ),
    )
    assertFalse(stateDispatcher.accept(ReqwsProjectState.DISPOSED, isUsable, render))
    edt.runQueued(0)
    assertFalse(
      stateDispatcher.accept(
        ReqwsProjectState(lifecycle = ReqwsLifecycleState.SYNCHRONIZED),
        isUsable,
        render,
      ),
    )

    assertEquals(emptyList<ReqwsLifecycleState>(), rendered)
    assertEquals(0, edt.queued.size)
  }

  @Test
  fun `dispose invalidates queued action and rejects later submissions`() {
    val edt = FakeEdt()
    val rendered = mutableListOf<String>()

    assertTrue(edt.dispatcher.submit { rendered += "queued" })
    edt.dispatcher.dispose()
    edt.runQueued(0)
    assertFalse(edt.dispatcher.submit { rendered += "after-dispose" })

    assertEquals(emptyList<String>(), rendered)
    assertEquals(0, edt.queued.size)
  }

  private class FakeEdt {
    var isEventDispatchThread = false
    val queued = mutableListOf<() -> Unit>()
    val dispatcher = LatestOnlyEdtDispatcher(
      isEventDispatchThread = { isEventDispatchThread },
      invokeLater = queued::add,
    )

    fun runQueued(index: Int) {
      val action = queued.removeAt(index)
      val previous = isEventDispatchThread
      isEventDispatchThread = true
      try {
        action()
      } finally {
        isEventDispatchThread = previous
      }
    }
  }
}
