package com.reqws.goland.ui

import com.reqws.goland.manifest.ManifestErrorCode
import com.reqws.goland.project.ReqwsLifecycleState
import com.reqws.goland.project.ReqwsProjectError
import com.reqws.goland.project.ReqwsProjectState
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.ArrayDeque

class ReqwsToolWindowAvailabilityControllerTest {
  @Test
  fun `updates availability from service states before content creation`() {
    val pending = ArrayDeque<() -> Unit>()
    val availability = mutableListOf<Boolean>()
    val controller = ReqwsToolWindowAvailabilityController(
      isProjectDisposed = { false },
      isToolWindowDisposed = { false },
      dispatchOnEdt = { action -> pending.addLast(action) },
      setAvailable = { available -> availability.add(available) },
    )

    controller.accept(ReqwsProjectState.INACTIVE)
    controller.accept(ReqwsProjectState(ReqwsLifecycleState.READING))
    controller.accept(
      ReqwsProjectState(
        lifecycle = ReqwsLifecycleState.ERROR,
        lastError = ReqwsProjectError(ManifestErrorCode.MANIFEST_INVALID_JSON.name),
      ),
    )
    controller.accept(ReqwsProjectState.INACTIVE)
    while (pending.isNotEmpty()) pending.removeFirst().invoke()

    assertEquals(listOf(false, false, true, false), availability)
  }

  @Test
  fun `drops queued availability updates after disposal`() {
    val pending = ArrayDeque<() -> Unit>()
    val availability = mutableListOf<Boolean>()
    val controller = ReqwsToolWindowAvailabilityController(
      isProjectDisposed = { false },
      isToolWindowDisposed = { false },
      dispatchOnEdt = { action -> pending.addLast(action) },
      setAvailable = { available -> availability.add(available) },
    )

    controller.accept(
      ReqwsProjectState(
        lifecycle = ReqwsLifecycleState.ERROR,
        lastError = ReqwsProjectError(ManifestErrorCode.MANIFEST_INVALID_JSON.name),
      ),
    )
    controller.dispose()
    pending.removeFirst().invoke()

    assertEquals(emptyList<Boolean>(), availability)
  }

  @Test
  fun `drops availability updates after the tool window is disposed`() {
    val pending = ArrayDeque<() -> Unit>()
    val availability = mutableListOf<Boolean>()
    var toolWindowDisposed = false
    val controller = ReqwsToolWindowAvailabilityController(
      isProjectDisposed = { false },
      isToolWindowDisposed = { toolWindowDisposed },
      dispatchOnEdt = { action -> pending.addLast(action) },
      setAvailable = { available -> availability.add(available) },
    )

    controller.accept(
      ReqwsProjectState(
        lifecycle = ReqwsLifecycleState.ERROR,
        lastError = ReqwsProjectError(ManifestErrorCode.MANIFEST_INVALID_JSON.name),
      ),
    )
    toolWindowDisposed = true
    pending.removeFirst().invoke()

    assertEquals(emptyList<Boolean>(), availability)
  }

  @Test
  fun `ignores the terminal disposed state`() {
    val availability = mutableListOf<Boolean>()
    val controller = ReqwsToolWindowAvailabilityController(
      isProjectDisposed = { false },
      isToolWindowDisposed = { false },
      dispatchOnEdt = { action -> action() },
      setAvailable = { available -> availability.add(available) },
    )

    controller.accept(ReqwsProjectState.DISPOSED)

    assertEquals(emptyList<Boolean>(), availability)
  }
}
