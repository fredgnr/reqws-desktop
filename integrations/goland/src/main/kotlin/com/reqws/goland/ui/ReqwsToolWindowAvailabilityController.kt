package com.reqws.goland.ui

import com.intellij.openapi.Disposable
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindowManager
import com.reqws.goland.project.ReqwsProjectService
import com.reqws.goland.project.ReqwsProjectState
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import javax.swing.SwingUtilities

internal const val REQWS_TOOL_WINDOW_ID = "ReqWS"

/** Keeps Tool Window availability synchronized even before its lazy content is created. */
internal class ReqwsToolWindowAvailabilityController(
  private val isProjectDisposed: () -> Boolean,
  private val isToolWindowDisposed: () -> Boolean,
  private val dispatchOnEdt: (() -> Unit) -> Unit,
  private val setAvailable: (Boolean) -> Unit,
) : Disposable {
  private val disposed = AtomicBoolean(false)
  private val listenerHandle = AtomicReference<AutoCloseable?>(null)

  fun bind(service: ReqwsProjectService) {
    if (disposed.get()) return
    val handle = service.addListener(::accept)
    if (!listenerHandle.compareAndSet(null, handle)) {
      handle.close()
      return
    }
    if (disposed.get()) listenerHandle.getAndSet(null)?.close()
  }

  internal fun accept(state: ReqwsProjectState) {
    if (disposed.get() || state.lifecycle == com.reqws.goland.project.ReqwsLifecycleState.DISPOSED) return
    val visible = ReqwsToolWindowViewModel.from(state).visible
    dispatchOnEdt {
      if (!disposed.get() && !isProjectDisposed() && !isToolWindowDisposed()) {
        setAvailable(visible)
      }
    }
  }

  override fun dispose() {
    if (!disposed.compareAndSet(false, true)) return
    listenerHandle.getAndSet(null)?.close()
  }

  companion object {
    fun forProject(project: Project): ReqwsToolWindowAvailabilityController =
      ReqwsToolWindowAvailabilityController(
        isProjectDisposed = { project.isDisposed },
        isToolWindowDisposed = {
          if (project.isDisposed) {
            true
          } else {
            ToolWindowManager.getInstance(project)
              .getToolWindow(REQWS_TOOL_WINDOW_ID)
              ?.isDisposed == true
          }
        },
        dispatchOnEdt = { action ->
          if (SwingUtilities.isEventDispatchThread()) action() else SwingUtilities.invokeLater(action)
        },
        setAvailable = { available ->
          ToolWindowManager.getInstance(project)
            .getToolWindow(REQWS_TOOL_WINDOW_ID)
            ?.setAvailable(available)
        },
      )
  }
}
