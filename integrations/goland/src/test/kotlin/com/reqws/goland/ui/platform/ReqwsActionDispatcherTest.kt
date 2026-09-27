package com.reqws.goland.ui.platform

import com.reqws.goland.project.ReqwsLifecycleState
import com.reqws.goland.project.ReqwsProjectState
import com.reqws.goland.ui.state.ReqwsUiAction
import org.junit.Assert.*
import org.junit.Test

class ReqwsActionDispatcherTest {
  @Test
  fun `every action rechecks disposal and fresh eligibility`() {
    var current = ReqwsProjectState(ReqwsLifecycleState.ERROR)
    var usable = true
    var effects = 0
    val dispatcher = ReqwsActionDispatcher({ usable }, { current }, { effects++ }, { effects++ }, { effects++ })
    current = ReqwsProjectState.DISPOSED
    ReqwsUiAction.entries.forEach(dispatcher::dispatch)
    assertEquals(0, effects)
    current = ReqwsProjectState(ReqwsLifecycleState.ERROR)
    usable = false
    ReqwsUiAction.entries.forEach(dispatcher::dispatch)
    assertEquals(0, effects)
    usable = true
    ReqwsUiAction.entries.forEach(dispatcher::dispatch)
    assertEquals(3, effects)
  }

  @Test
  fun `copy uses exactly one snapshot even if the service advances during the operation`() {
    val before = ReqwsProjectState(ReqwsLifecycleState.ERROR)
    var current = before
    var reads = 0
    var copied: ReqwsProjectState? = null
    val dispatcher = ReqwsActionDispatcher({ true }, { reads++; current }, {}, {}, {
      current = ReqwsProjectState.DISPOSED
      copied = it
    })
    assertSame(before, dispatcher.dispatch(ReqwsUiAction.CopyDiagnostics))
    assertSame(before, copied)
    assertEquals(1, reads)
  }

  @Test
  fun `sync delegates repeated requests to the same existing route without launching a second engine`() {
    var requests = 0
    val dispatcher = ReqwsActionDispatcher({ true }, { ReqwsProjectState(ReqwsLifecycleState.SYNCHRONIZING) }, { requests++ }, {}, {})
    repeat(3) { dispatcher.dispatch(ReqwsUiAction.SyncNow) }
    assertEquals(3, requests)
  }
}
