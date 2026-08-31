package com.reqws.goland.watch

import com.intellij.openapi.Disposable
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.openapi.vfs.newvfs.BulkFileListener
import com.intellij.openapi.vfs.newvfs.events.VFileEvent
import com.reqws.goland.sync.DebounceWaiter
import com.reqws.goland.sync.DebouncedAction
import com.reqws.goland.sync.LatestDebouncer
import com.reqws.goland.sync.ManifestVfsEventFilter
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch

internal fun interface ManifestSyncRequest {
  /** Must enqueue the shared sync pipeline without performing blocking work in this callback. */
  fun requestSync()
}

internal fun interface ManifestNativeWatch : AutoCloseable {
  override fun close()
}

internal fun interface ManifestNativeWatchRegistrar {
  fun watch(directory: Path): ManifestNativeWatch
}

internal fun interface ManifestVfsRefresh {
  fun refresh(path: Path)
}

internal fun interface ManifestVfsRefreshFactory {
  fun create(manifestPath: Path): ManifestVfsRefresh
}

internal fun manifestWatcherFailureLogMessage(failure: Throwable): String =
  "ReqWS manifest watcher refresh failed; automatic refresh will retry " +
    "(code=MANIFEST_WATCH_REFRESH_FAILED, type=${failure::class.java.name})."

internal interface ManifestVfsEventConnection : AutoCloseable {
  fun subscribe(listener: BulkFileListener)

  override fun close()
}

internal fun interface ManifestVfsEventConnector {
  fun connect(project: Project, coroutineScope: CoroutineScope): ManifestVfsEventConnection
}

private object LocalManifestNativeWatchRegistrar : ManifestNativeWatchRegistrar {
  override fun watch(directory: Path): ManifestNativeWatch {
    val fileSystem = LocalFileSystem.getInstance()
    val request = requireNotNull(fileSystem.addRootToWatch(directory.toString(), false)) {
      "Unable to register the ReqWS manifest directory with the local filesystem watcher."
    }
    return ManifestNativeWatch { fileSystem.removeWatchedRoot(request) }
  }
}

private object ProjectManifestVfsEventConnector : ManifestVfsEventConnector {
  override fun connect(
    project: Project,
    coroutineScope: CoroutineScope,
  ): ManifestVfsEventConnection {
    val connection = project.messageBus.connect(coroutineScope)
    return object : ManifestVfsEventConnection {
      override fun subscribe(listener: BulkFileListener) {
        connection.subscribe(VirtualFileManager.VFS_CHANGES, listener)
      }

      override fun close() {
        connection.disconnect()
      }
    }
  }
}

private class LocalManifestVfsRefresh(
  private val manifestPath: Path,
) : ManifestVfsRefresh {
  private val fileSystem = LocalFileSystem.getInstance()
  private val manifestDirectory = requireNotNull(manifestPath.parent)
  // Watcher construction runs on the shared refresh pipeline and must not synchronously wait for
  // an IDE VFS refresh. The lifecycle-owned pump performs the first active refresh on its tick.
  private var manifestFile = fileSystem.findFileByNioFile(manifestPath)
  private var manifestDirectoryFile = fileSystem.findFileByNioFile(manifestDirectory)

  override fun refresh(path: Path) {
    check(path == manifestPath) { "The ReqWS VFS refresh target changed unexpectedly." }
    manifestFile?.refresh(false, false)
    manifestDirectoryFile?.refresh(false, false)
    manifestDirectoryFile = fileSystem.refreshAndFindFileByNioFile(manifestDirectory)
    manifestFile = fileSystem.refreshAndFindFileByNioFile(manifestPath)
  }
}

private object LocalManifestVfsRefreshFactory : ManifestVfsRefreshFactory {
  override fun create(manifestPath: Path): ManifestVfsRefresh =
    LocalManifestVfsRefresh(manifestPath)
}

/** Runs every cleanup exactly once and retains later failures as suppressed exceptions. */
private fun cleanupResources(
  primaryFailure: Throwable? = null,
  vararg cleanups: () -> Unit,
): Throwable? {
  var firstFailure = primaryFailure
  cleanups.forEach { cleanup ->
    try {
      cleanup()
    } catch (cleanupFailure: Throwable) {
      val currentFailure = firstFailure
      if (currentFailure == null) {
        firstFailure = cleanupFailure
      } else if (currentFailure !== cleanupFailure) {
        currentFailure.addSuppressed(cleanupFailure)
      }
    }
  }
  return firstFailure
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
  private val onFailure: (Throwable) -> Unit = { failure ->
    // Throwable messages and stack frames may contain full workspace/home paths. The default log
    // carries only a stable code and exception type; tests may still inject onFailure directly.
    LOG.warn(manifestWatcherFailureLogMessage(failure))
  },
  nativeWatchRegistrar: ManifestNativeWatchRegistrar = LocalManifestNativeWatchRegistrar,
  refreshWaiter: DebounceWaiter? = null,
  vfsRefreshFactory: ManifestVfsRefreshFactory = LocalManifestVfsRefreshFactory,
  vfsEventConnector: ManifestVfsEventConnector = ProjectManifestVfsEventConnector,
) : Disposable {
  private val disposed = AtomicBoolean(false)
  private val refreshFailureReported = AtomicBoolean(false)
  private val callbackLock = Any()
  private val filter = ManifestVfsEventFilter(manifestPath)
  private val debouncer = LatestDebouncer(
    scope = coroutineScope,
    waiter = debounceWaiter ?: DebounceWaiter { kotlinx.coroutines.delay(it) },
    action = DebouncedAction<Unit> { dispatchSyncRequest() },
    onFailure = onFailure,
  )
  private val nativeWatch: ManifestNativeWatch
  private val connection: ManifestVfsEventConnection
  private val refreshJob: Job

  init {
    var pendingNativeWatch: ManifestNativeWatch? = null
    var pendingConnection: ManifestVfsEventConnection? = null
    var pendingRefreshJob: Job? = null
    try {
      val installedNativeWatch = nativeWatchRegistrar.watch(
        requireNotNull(manifestPath.toAbsolutePath().normalize().parent) {
          "The ReqWS manifest path must have a parent directory."
        },
      )
      pendingNativeWatch = installedNativeWatch

      val installedConnection = vfsEventConnector.connect(project, coroutineScope)
      pendingConnection = installedConnection
      installedConnection.subscribe(
        object : BulkFileListener {
          override fun after(events: List<VFileEvent>) {
            handleAfterEvents(events)
          }
        },
      )

      val effectiveVfsRefresh = vfsRefreshFactory.create(manifestPath)
      val waiter = refreshWaiter ?: DebounceWaiter { kotlinx.coroutines.delay(it) }
      val installedRefreshJob = coroutineScope.launch(Dispatchers.IO) {
        while (!disposed.get()) {
          waiter.await(VFS_REFRESH_INTERVAL_MILLIS)
          currentCoroutineContext().ensureActive()
          if (disposed.get()) return@launch
          try {
            effectiveVfsRefresh.refresh(manifestPath)
            refreshFailureReported.set(false)
          } catch (failure: CancellationException) {
            throw failure
          } catch (failure: Exception) {
            if (refreshFailureReported.compareAndSet(false, true)) {
              notifyFailure(failure)
            }
          }
        }
      }
      pendingRefreshJob = installedRefreshJob

      nativeWatch = installedNativeWatch
      connection = installedConnection
      refreshJob = installedRefreshJob
    } catch (failure: Throwable) {
      cleanupResources(
        failure,
        { pendingRefreshJob?.cancel() },
        { pendingConnection?.close() },
        { pendingNativeWatch?.close() },
        { debouncer.close() },
      )
      throw failure
    }
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
        LOG.info("ReqWS manifest change detected; scheduling automatic synchronization.")
        syncRequest.requestSync()
      }
    }
  }

  private fun notifyFailure(failure: Throwable) {
    try {
      onFailure(failure)
    } catch (_: Exception) {
      // A reporting failure must not terminate future targeted refreshes.
    }
  }

  override fun dispose() {
    val debounceFailure = synchronized(callbackLock) {
      if (!disposed.compareAndSet(false, true)) return
      cleanupResources(null, { debouncer.close() })
    }
    cleanupResources(
      debounceFailure,
      { refreshJob.cancel() },
      { connection.close() },
      { nativeWatch.close() },
    )?.let { throw it }
  }

  internal companion object {
    const val VFS_REFRESH_INTERVAL_MILLIS = 1_000L
    val LOG: Logger = Logger.getInstance(ManifestVfsWatcher::class.java)
  }
}
