package com.reqws.goland.watch

import com.intellij.openapi.Disposable
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.openapi.vfs.newvfs.BulkFileListener
import com.intellij.openapi.vfs.newvfs.events.VFileEvent
import com.intellij.util.messages.SimpleMessageBusConnection
import com.reqws.goland.sync.DebounceWaiter
import com.reqws.goland.sync.DebouncedAction
import com.reqws.goland.sync.LatestDebouncer
import com.reqws.goland.sync.ManifestVfsEventFilter
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope

internal fun interface ManifestSyncRequest {
  /** Must enqueue the shared sync pipeline without performing blocking work in this callback. */
  fun requestSync()
}

/**
 * Project-scoped adapter for application VFS events.
 *
 * It performs only path translation and filtering in the listener callback. Reading the
 * manifest and applying changes remain the responsibility of the injected sync pipeline.
 */
internal class ManifestVfsWatcher(
  project: Project,
  manifestPath: Path,
  coroutineScope: CoroutineScope,
  private val syncRequest: ManifestSyncRequest,
  debounceWaiter: DebounceWaiter? = null,
  onFailure: (Throwable) -> Unit = {},
) : Disposable {
  private val disposed = AtomicBoolean(false)
  private val callbackLock = Any()
  private val filter = ManifestVfsEventFilter(manifestPath)
  private val debouncer = LatestDebouncer(
    scope = coroutineScope,
    waiter = debounceWaiter ?: DebounceWaiter { kotlinx.coroutines.delay(it) },
    action = DebouncedAction<Unit> { dispatchSyncRequest() },
    onFailure = onFailure,
  )
  private val connection: SimpleMessageBusConnection = project.messageBus
    .connect(coroutineScope)
    .also { connection ->
      connection.subscribe(
        VirtualFileManager.VFS_CHANGES,
        object : BulkFileListener {
          override fun after(events: List<VFileEvent>) {
            handleAfterEvents(events)
          }
        },
      )
    }

  val isDisposed: Boolean
    get() = disposed.get()

  private fun handleAfterEvents(events: List<VFileEvent>) {
    if (disposed.get()) return
    val manifestMayHaveChanged = events.asSequence()
      .mapNotNull(PlatformVfsEventTranslator::translate)
      .any(filter::accepts)
    if (manifestMayHaveChanged) {
      debouncer.submit(Unit)
    }
  }

  private fun dispatchSyncRequest() {
    synchronized(callbackLock) {
      if (!disposed.get()) {
        syncRequest.requestSync()
      }
    }
  }

  override fun dispose() {
    synchronized(callbackLock) {
      if (!disposed.compareAndSet(false, true)) return
      debouncer.close()
    }
    connection.disconnect()
  }
}
