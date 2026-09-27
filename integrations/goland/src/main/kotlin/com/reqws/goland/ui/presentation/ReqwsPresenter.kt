package com.reqws.goland.ui.presentation

import com.reqws.goland.project.ReqwsLifecycleState
import com.reqws.goland.project.ReqwsProjectState
import com.reqws.goland.ui.state.ReqwsUiStateMapper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** One presenter per Content, independent of compositions (including hide/show). */
internal class ReqwsPresenter(
  private val scope: CoroutineScope,
  private val feedbackDurationMillis: Long = 2_500,
) : AutoCloseable {
  private val lock = Any()
  private val mutableState = MutableStateFlow(ReqwsUiStateMapper.map(ReqwsProjectState.INACTIVE))
  val state = mutableState.asStateFlow()
  private var bound = false
  private var closed = false
  private var terminal = false
  private var current: ReqwsProjectState? = null
  private var registration: AutoCloseable? = null
  private var feedback: Job? = null
  private var feedbackVersion = 0L

  /** Subscribe exactly once; the publisher supplies both the initial and later ordered values. */
  fun bind(subscribe: ((ReqwsProjectState) -> Unit) -> AutoCloseable) {
    synchronized(lock) {
      if (bound || closed) return
      bound = true
    }
    val handle = try {
      subscribe(::accept)
    } catch (failure: Throwable) {
      close()
      throw failure
    }
    val closeNow = synchronized(lock) {
      if (closed || terminal) true else {
        registration = handle
        false
      }
    }
    if (closeNow) handle.close()
  }

  private fun accept(next: ReqwsProjectState) {
    val toClose = synchronized(lock) {
      if (closed || terminal) return
      // Mapping is synchronous inside the ordered callback, with no queued stale result.
      current = next
      feedbackVersion++
      feedback?.cancel()
      feedback = null
      mutableState.value = ReqwsUiStateMapper.map(next)
      terminal = next.lifecycle == ReqwsLifecycleState.DISPOSED
      if (terminal) registration.also { registration = null } else null
    }
    toClose?.close()
    if (next.lifecycle == ReqwsLifecycleState.DISPOSED) scope.cancel()
  }

  /** Only acknowledge the exact state copied by the platform; newer errors retain their details. */
  fun diagnosticsCopied(copied: ReqwsProjectState) {
    synchronized(lock) {
      if (closed || terminal || copied !== current || !mutableState.value.copyDiagnosticsEnabled) return
      feedback?.cancel()
      val version = ++feedbackVersion
      mutableState.value = mutableState.value.copy(diagnosticsCopied = true)
      feedback = scope.launch {
        delay(feedbackDurationMillis)
        synchronized(lock) {
          if (!closed && !terminal && version == feedbackVersion) {
            mutableState.value = mutableState.value.copy(diagnosticsCopied = false)
          }
        }
      }
    }
  }

  override fun close() {
    val toClose = synchronized(lock) {
      if (closed) return
      closed = true
      feedbackVersion++
      registration.also { registration = null }
    }
    try {
      toClose?.close()
    } finally {
      scope.cancel()
    }
  }
}
