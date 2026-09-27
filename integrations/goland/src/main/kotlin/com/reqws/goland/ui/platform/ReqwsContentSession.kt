package com.reqws.goland.ui.platform

import com.intellij.openapi.Disposable
import com.reqws.goland.project.ReqwsProjectState
import com.reqws.goland.ui.presentation.ReqwsPresenter
import com.reqws.goland.ui.state.ReqwsUiAction
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope

internal class ReqwsContentSession(scope: CoroutineScope) : Disposable {
  private val closed = AtomicBoolean(false)
  val isDisposed: Boolean get() = closed.get()
  val presenter = ReqwsPresenter(scope)

  fun bind(subscribe: ((ReqwsProjectState) -> Unit) -> AutoCloseable) {
    if (!isDisposed) presenter.bind(subscribe)
  }

  fun dispatch(action: ReqwsUiAction, dispatcher: ReqwsActionDispatcher) {
    if (isDisposed) return
    dispatcher.dispatch(action)?.let(presenter::diagnosticsCopied)
  }

  override fun dispose() {
    if (closed.compareAndSet(false, true)) presenter.close()
  }
}
