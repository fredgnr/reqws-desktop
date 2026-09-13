package com.reqws.goland.vcs

import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import com.intellij.openapi.vcs.ProjectLevelVcsManager
import com.intellij.openapi.vcs.VcsMappingListener
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean

/** Bridges public VCS configuration events to the ReqWS read-only diagnostics refresh path. */
@Service(Service.Level.PROJECT)
internal class ReqwsVcsConfigurationMonitor(
  project: Project,
) : Disposable {
  private val disposed = AtomicBoolean(false)
  private val externalListeners = CopyOnWriteArrayList<() -> Unit>()

  init {
    project.messageBus.connect(this).subscribe(
      ProjectLevelVcsManager.VCS_CONFIGURATION_CHANGED,
      VcsMappingListener(::configurationChanged),
    )
  }

  fun addExternalChangeListener(listener: () -> Unit): AutoCloseable {
    if (disposed.get()) return AutoCloseable {}
    externalListeners.add(listener)
    if (disposed.get()) {
      externalListeners.remove(listener)
      return AutoCloseable {}
    }
    return AutoCloseable { externalListeners.remove(listener) }
  }

  private fun configurationChanged() {
    if (disposed.get()) return
    externalListeners.forEach { listener ->
      if (disposed.get()) return
      try {
        listener()
      } catch (_: Exception) {
        // A faulty observer must not break the platform's VCS configuration publisher.
      }
    }
  }

  override fun dispose() {
    disposed.set(true)
    externalListeners.clear()
  }
}
