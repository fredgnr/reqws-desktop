package com.reqws.goland.ui

import com.intellij.openapi.Disposable
import com.reqws.goland.project.ReqwsLifecycleState
import com.reqws.goland.project.ReqwsProjectState
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import javax.swing.SwingUtilities

/** Delivers only the newest submitted action and invalidates queued work when disposed. */
internal class LatestOnlyEdtDispatcher(
  private val isEventDispatchThread: () -> Boolean = SwingUtilities::isEventDispatchThread,
  private val invokeLater: (() -> Unit) -> Unit = { action -> SwingUtilities.invokeLater(action) },
) : Disposable {
  private val disposed = AtomicBoolean(false)
  private val latestSequence = AtomicLong(0)

  fun submit(action: () -> Unit): Boolean {
    if (disposed.get()) return false
    val sequence = latestSequence.incrementAndGet()
    if (disposed.get()) return false

    val deliver = {
      if (!disposed.get() && latestSequence.get() == sequence) action()
    }
    if (isEventDispatchThread()) {
      deliver()
    } else {
      invokeLater(deliver)
    }
    return true
  }

  override fun dispose() {
    if (!disposed.compareAndSet(false, true)) return
    latestSequence.incrementAndGet()
  }
}

/** Applies Tool Window terminal-state semantics on top of latest-only EDT delivery. */
internal class ReqwsToolWindowStateDispatcher(
  private val edtDispatcher: LatestOnlyEdtDispatcher = LatestOnlyEdtDispatcher(),
) : Disposable {
  fun accept(
    state: ReqwsProjectState,
    isUsable: () -> Boolean,
    render: (ReqwsProjectState) -> Unit,
  ): Boolean {
    if (state.lifecycle == ReqwsLifecycleState.DISPOSED) {
      edtDispatcher.dispose()
      return false
    }
    if (!isUsable()) return false
    return edtDispatcher.submit {
      if (isUsable()) render(state)
    }
  }

  override fun dispose() {
    edtDispatcher.dispose()
  }
}
