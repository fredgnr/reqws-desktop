package com.reqws.goland.watch

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.newvfs.BulkFileListener
import com.intellij.openapi.vfs.newvfs.events.VFileDeleteEvent
import com.intellij.openapi.vfs.newvfs.events.VFileEvent
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.util.concurrency.AppExecutorUtil
import com.reqws.goland.diagnostics.ReqwsSyncTrace
import com.reqws.goland.diagnostics.SyncTraceEvent
import com.reqws.goland.sync.DebounceWaiter
import com.reqws.goland.sync.LatestDebouncer
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.concurrent.Callable
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.yield

class ManifestVfsWatcherTest : BasePlatformTestCase() {
  fun testDefaultFailureLogMessageOmitsThrowableMessageAndWorkspacePath() {
    val sensitivePath = "/Users/example/private-workspace/.reqws/workspace.json"
    val failure = IllegalStateException("failed to refresh $sensitivePath")

    val message = manifestWatcherFailureLogMessage(failure)

    assertTrue(message.contains("code=MANIFEST_WATCH_REFRESH_FAILED"))
    assertTrue(message.contains(IllegalStateException::class.java.name))
    assertFalse(message.contains(failure.message.orEmpty()))
    assertFalse(message.contains(sensitivePath))
  }

  fun testRegistersTheExcludedManifestDirectoryWithTheNativeWatcherAndReleasesIt() = runBlocking {
    val root = myFixture.tempDirFixture.findOrCreateDir("watch-native")
    val manifestPath = Path.of(root.path).resolve(".reqws/workspace.json")
    var watchedDirectory: Path? = null
    val closeCount = AtomicInteger()
    val watcher = ManifestVfsWatcher(
      project = project,
      manifestPath = manifestPath,
      coroutineScope = this,
      syncRequest = ManifestSyncRequest {},
      nativeWatchRegistrar = ManifestNativeWatchRegistrar { directory ->
        watchedDirectory = directory
        ManifestNativeWatch { closeCount.incrementAndGet() }
      },
    )

    assertEquals(manifestPath.parent, watchedDirectory)
    watcher.dispose()
    watcher.dispose()
    assertEquals(1, closeCount.get())
  }

  fun testConnectionFailureRollsBackTheNativeWatchBeforeRefreshConstruction() = runBlocking {
    val root = myFixture.tempDirFixture.findOrCreateDir("watch-connect-failure")
    val manifestPath = Path.of(root.path).resolve(".reqws/workspace.json")
    val expectedFailure = IllegalStateException("synthetic connection failure")
    val nativeCloseCount = AtomicInteger()
    val refreshFactoryCount = AtomicInteger()

    val failure = captureFailure {
      ManifestVfsWatcher(
        project = project,
        manifestPath = manifestPath,
        coroutineScope = this,
        syncRequest = ManifestSyncRequest {},
        nativeWatchRegistrar = ManifestNativeWatchRegistrar {
          ManifestNativeWatch { nativeCloseCount.incrementAndGet() }
        },
        vfsRefreshFactory = ManifestVfsRefreshFactory {
          refreshFactoryCount.incrementAndGet()
          ManifestVfsRefresh {}
        },
        vfsEventConnector = ManifestVfsEventConnector { _, _ -> throw expectedFailure },
      )
    }

    assertSame(expectedFailure, failure)
    assertEquals(1, nativeCloseCount.get())
    assertEquals(0, refreshFactoryCount.get())
  }

  fun testSubscriptionFailureDisconnectsAndReleasesTheNativeWatch() = runBlocking {
    val root = myFixture.tempDirFixture.findOrCreateDir("watch-subscribe-failure")
    val manifestPath = Path.of(root.path).resolve(".reqws/workspace.json")
    val expectedFailure = IllegalStateException("synthetic subscription failure")
    val nativeCloseCount = AtomicInteger()
    val refreshFactoryCount = AtomicInteger()
    val connection = RecordingManifestVfsEventConnection(subscribeFailure = expectedFailure)

    val failure = captureFailure {
      ManifestVfsWatcher(
        project = project,
        manifestPath = manifestPath,
        coroutineScope = this,
        syncRequest = ManifestSyncRequest {},
        nativeWatchRegistrar = ManifestNativeWatchRegistrar {
          ManifestNativeWatch { nativeCloseCount.incrementAndGet() }
        },
        vfsRefreshFactory = ManifestVfsRefreshFactory {
          refreshFactoryCount.incrementAndGet()
          ManifestVfsRefresh {}
        },
        vfsEventConnector = ManifestVfsEventConnector { _, _ -> connection },
      )
    }

    assertSame(expectedFailure, failure)
    assertEquals(1, connection.subscribeCount)
    assertEquals(1, connection.closeCount)
    assertEquals(1, nativeCloseCount.get())
    assertEquals(0, refreshFactoryCount.get())
  }

  fun testRefreshConstructionFailureRunsEveryRollbackAndSuppressesCleanupFailures() = runBlocking {
    val root = myFixture.tempDirFixture.findOrCreateDir("watch-refresh-construction-failure")
    val manifestPath = Path.of(root.path).resolve(".reqws/workspace.json")
    val expectedFailure = IllegalStateException("synthetic refresh construction failure")
    val connectionCloseFailure = IllegalStateException("synthetic connection close failure")
    val nativeCloseFailure = IllegalStateException("synthetic native close failure")
    val nativeCloseCount = AtomicInteger()
    val connection = RecordingManifestVfsEventConnection(closeFailure = connectionCloseFailure)

    val failure = captureFailure {
      ManifestVfsWatcher(
        project = project,
        manifestPath = manifestPath,
        coroutineScope = this,
        syncRequest = ManifestSyncRequest {},
        nativeWatchRegistrar = ManifestNativeWatchRegistrar {
          ManifestNativeWatch {
            nativeCloseCount.incrementAndGet()
            throw nativeCloseFailure
          }
        },
        vfsRefreshFactory = ManifestVfsRefreshFactory { throw expectedFailure },
        vfsEventConnector = ManifestVfsEventConnector { _, _ -> connection },
      )
    }

    assertSame(expectedFailure, failure)
    assertEquals(1, connection.subscribeCount)
    assertEquals(1, connection.closeCount)
    assertEquals(1, nativeCloseCount.get())
    assertEquals(2, failure.suppressed.size)
    assertSame(connectionCloseFailure, failure.suppressed[0])
    assertSame(nativeCloseFailure, failure.suppressed[1])
  }

  fun testRefreshesOnlyTheFixedManifestPathAndStopsThePumpOnDispose() = runBlocking {
    val manifest = myFixture.tempDirFixture.createFile("watch-refresh/.reqws/workspace.json", "{}")
    val manifestPath = Path.of(manifest.path)
    val refreshWaiter = ControlledWaiter()
    val refreshedPaths = Channel<Path>(Channel.UNLIMITED)
    val traceLines = CopyOnWriteArrayList<String>()
    val watcher = ManifestVfsWatcher(
      project = project,
      manifestPath = manifestPath,
      coroutineScope = this,
      syncRequest = ManifestSyncRequest {},
      refreshWaiter = refreshWaiter,
      vfsRefreshFactory = ManifestVfsRefreshFactory {
        ManifestVfsRefresh { refreshedPaths.trySend(it) }
      },
      trace = ReqwsSyncTrace.testing(sink = { traceLines += it }),
    )

    val firstGate = refreshWaiter.nextGate()
    assertEquals(ManifestVfsWatcher.VFS_REFRESH_INTERVAL_MILLIS, firstGate.delayMillis)
    firstGate.release.complete(Unit)
    assertEquals(manifestPath, refreshedPaths.receive())

    val secondGate = refreshWaiter.nextGate()
    watcher.dispose()
    secondGate.release.complete(Unit)
    yield()
    assertTrue(refreshedPaths.tryReceive().isFailure)
    assertEquals(1, traceEvents(traceLines, SyncTraceEvent.WATCH_REFRESH_START).size)
    assertEquals(1, traceEvents(traceLines, SyncTraceEvent.WATCH_REFRESH_END).size)
    assertTrue(
      traceEvents(traceLines, SyncTraceEvent.WATCH_REFRESH_START).single()
        .contains("target_scope=FIXED_MANIFEST_AND_PARENT"),
    )
    assertTrue(
      traceEvents(traceLines, SyncTraceEvent.WATCH_REFRESH_END).single()
        .contains("outcome=COMPLETED"),
    )
    assertEquals(1, traceEvents(traceLines, SyncTraceEvent.WATCHER_DISPOSED).size)
    assertTrue(traceEvents(traceLines, SyncTraceEvent.WATCH_DISPATCH).isEmpty())
    assertFalse(traceLines.any { it.contains(manifestPath.toString()) })
  }

  fun testDisabledTraceKeepsTheFirstMatchingEventShortCircuit() = runBlocking {
    val manifest = myFixture.tempDirFixture.createFile("watch-trace-off/.reqws/workspace.json", "{}")
    val connection = RecordingManifestVfsEventConnection()
    val waiter = ControlledWaiter()
    val requests = Channel<Unit>(Channel.UNLIMITED)
    val watcher = ManifestVfsWatcher(
      project = project,
      manifestPath = Path.of(manifest.path),
      coroutineScope = this,
      syncRequest = ManifestSyncRequest { requests.trySend(Unit) },
      debounceWaiter = waiter,
      refreshWaiter = ControlledWaiter(),
      nativeWatchRegistrar = ManifestNativeWatchRegistrar { ManifestNativeWatch {} },
      vfsRefreshFactory = ManifestVfsRefreshFactory { ManifestVfsRefresh {} },
      vfsEventConnector = ManifestVfsEventConnector { _, _ -> connection },
      trace = ReqwsSyncTrace.testing(
        enabled = false,
        nanoTime = { throw AssertionError("Disabled tracing called its clock") },
        sink = { throw AssertionError("Disabled tracing called its sink") },
      ),
    )
    val firstMatch = VFileDeleteEvent(this, manifest, false)
    val events = object : AbstractList<VFileEvent>() {
      override val size = 2

      override fun get(index: Int): VFileEvent {
        assertEquals("Disabled tracing traversed beyond the first matching event", 0, index)
        return firstMatch
      }
    }

    try {
      connection.listener.after(events)
      waiter.nextGate().release.complete(Unit)
      requests.receive()
      assertTrue(requests.tryReceive().isFailure)
    } finally {
      watcher.dispose()
    }
  }

  fun testEnabledTraceSeparatesReceivedEventsFromOneDebouncedDispatch() = runBlocking {
    val manifest = myFixture.tempDirFixture.createFile("watch-trace-on/.reqws/workspace.json", "{}")
    val sibling = myFixture.tempDirFixture.createFile("watch-trace-on/.reqws/other.json", "{}")
    val connection = RecordingManifestVfsEventConnection()
    val waiter = ControlledWaiter()
    val requests = Channel<Unit>(Channel.UNLIMITED)
    val traceLines = CopyOnWriteArrayList<String>()
    val watcher = ManifestVfsWatcher(
      project = project,
      manifestPath = Path.of(manifest.path),
      coroutineScope = this,
      syncRequest = ManifestSyncRequest { requests.trySend(Unit) },
      debounceWaiter = waiter,
      refreshWaiter = ControlledWaiter(),
      nativeWatchRegistrar = ManifestNativeWatchRegistrar { ManifestNativeWatch {} },
      vfsRefreshFactory = ManifestVfsRefreshFactory { ManifestVfsRefresh {} },
      vfsEventConnector = ManifestVfsEventConnector { _, _ -> connection },
      trace = ReqwsSyncTrace.testing(sink = { traceLines += it }),
    )

    try {
      repeat(8) {
        connection.listener.after(
          listOf(VFileDeleteEvent(this, manifest, false), VFileDeleteEvent(this, sibling, false)),
        )
      }
      assertTrue(traceEvents(traceLines, SyncTraceEvent.WATCH_DISPATCH).isEmpty())
      waiter.nextGate().release.complete(Unit)
      requests.receive()
      yield()

      assertTrue(requests.tryReceive().isFailure)
      assertTrue(waiter.tryNextGate().isFailure)
      val batches = traceEvents(traceLines, SyncTraceEvent.VFS_BATCH)
      assertEquals(8, batches.size)
      assertTrue(batches.all { it.contains("received_count=2") && it.contains("matched_count=1") })
      assertEquals(1, traceEvents(traceLines, SyncTraceEvent.WATCH_DISPATCH).size)
      assertFalse(traceLines.any { it.contains(manifest.path) || it.contains(sibling.path) })
    } finally {
      watcher.dispose()
    }
    assertEquals(1, connection.closeCount)
  }

  fun testRefreshFailureIsReportedOnceUntilASuccessfulPumpTickRearmsReporting() = runBlocking {
    val manifest = myFixture.tempDirFixture.createFile("watch-refresh-recovery/.reqws/workspace.json", "{}")
    val manifestPath = Path.of(manifest.path)
    val expectedFailure = IllegalStateException("synthetic refresh failure")
    val refreshWaiter = ControlledWaiter()
    val refreshAttempts = AtomicInteger()
    val failures = Channel<Throwable>(Channel.UNLIMITED)
    val refreshedPaths = Channel<Path>(Channel.UNLIMITED)
    val watcher = ManifestVfsWatcher(
      project = project,
      manifestPath = manifestPath,
      coroutineScope = this,
      syncRequest = ManifestSyncRequest {},
      onFailure = { failures.trySend(it) },
      refreshWaiter = refreshWaiter,
      vfsRefreshFactory = ManifestVfsRefreshFactory {
        ManifestVfsRefresh { path ->
          when (refreshAttempts.incrementAndGet()) {
            2 -> refreshedPaths.trySend(path)
            else -> throw expectedFailure
          }
        }
      },
    )

    refreshWaiter.nextGate().release.complete(Unit)
    assertSame(expectedFailure, failures.receive())

    refreshWaiter.nextGate().release.complete(Unit)
    assertEquals(manifestPath, refreshedPaths.receive())

    refreshWaiter.nextGate().release.complete(Unit)
    assertSame(expectedFailure, failures.receive())
    refreshWaiter.nextGate().release.complete(Unit)
    withTimeout(10_000) {
      while (refreshAttempts.get() < 4) yield()
    }
    assertTrue(failures.tryReceive().isFailure)
    assertEquals(4, refreshAttempts.get())
    watcher.dispose()
  }

  fun testRefreshPlatformCancellationTerminatesThePumpWithTheSameInstance() = runBlocking {
    for (traceEnabled in listOf(false, true)) {
      assertRefreshCancellationStopsPump(ProcessCanceledException(), traceEnabled)
    }
  }

  fun testRefreshCoroutineCancellationTerminatesThePumpWithTheSameInstance() = runBlocking {
    for (traceEnabled in listOf(false, true)) {
      assertRefreshCancellationStopsPump(CancellationException("refresh cancelled"), traceEnabled)
    }
  }

  fun testFailureReporterCancellationDoesNotTerminateOrdinaryFailureRecovery() = runBlocking {
    for (reporterFailure in listOf(ProcessCanceledException(), CancellationException("reporter cancelled"))) {
      val root = myFixture.tempDirFixture.findOrCreateDir("watch-reporter-cancellation")
      val expectedFailure = IllegalStateException("ordinary refresh failure")
      val refreshWaiter = ControlledWaiter()
      val attempts = AtomicInteger()
      val reported = CopyOnWriteArrayList<Throwable>()
      val traceLines = CopyOnWriteArrayList<String>()
      val watcher = ManifestVfsWatcher(
        project = project,
        manifestPath = Path.of(root.path).resolve(".reqws/workspace.json"),
        coroutineScope = this,
        syncRequest = ManifestSyncRequest {},
        onFailure = { reported += it; throw reporterFailure },
        nativeWatchRegistrar = ManifestNativeWatchRegistrar { ManifestNativeWatch {} },
        refreshWaiter = refreshWaiter,
        vfsRefreshFactory = ManifestVfsRefreshFactory {
          ManifestVfsRefresh {
            if (attempts.incrementAndGet() == 1) throw expectedFailure
          }
        },
        vfsEventConnector = ManifestVfsEventConnector { _, _ -> RecordingManifestVfsEventConnection() },
        trace = ReqwsSyncTrace.testing(sink = traceLines::add),
      )
      try {
        withTimeout(5_000) { refreshWaiter.nextGate() }.release.complete(Unit)
        withTimeout(5_000) { refreshWaiter.nextGate() }.release.complete(Unit)
        withTimeout(5_000) { refreshWaiter.nextGate() }
        assertEquals(2, attempts.get())
        assertSame(expectedFailure, reported.single())
        val ends = traceEvents(traceLines, SyncTraceEvent.WATCH_REFRESH_END)
        assertEquals(2, ends.size)
        assertTrue(ends[0].contains("outcome=FAILED"))
        assertTrue(ends[1].contains("outcome=COMPLETED"))
      } finally {
        watcher.dispose()
      }
    }
  }

  fun testDisposeRunsEveryCleanupOnceAndStopsThePumpWhenCleanupThrows() = runBlocking {
    val manifest = myFixture.tempDirFixture.createFile("watch-dispose-failures/.reqws/workspace.json", "{}")
    val manifestPath = Path.of(manifest.path)
    val connectionCloseFailure = IllegalStateException("synthetic dispose connection failure")
    val nativeCloseFailure = IllegalStateException("synthetic dispose native failure")
    val connection = RecordingManifestVfsEventConnection(closeFailure = connectionCloseFailure)
    val nativeCloseCount = AtomicInteger()
    val refreshWaiter = ControlledWaiter()
    val refreshedPaths = Channel<Path>(Channel.UNLIMITED)
    val watcher = ManifestVfsWatcher(
      project = project,
      manifestPath = manifestPath,
      coroutineScope = this,
      syncRequest = ManifestSyncRequest {},
      nativeWatchRegistrar = ManifestNativeWatchRegistrar {
        ManifestNativeWatch {
          nativeCloseCount.incrementAndGet()
          throw nativeCloseFailure
        }
      },
      refreshWaiter = refreshWaiter,
      vfsRefreshFactory = ManifestVfsRefreshFactory {
        ManifestVfsRefresh { refreshedPaths.trySend(it) }
      },
      vfsEventConnector = ManifestVfsEventConnector { _, _ -> connection },
    )

    val pendingRefresh = refreshWaiter.nextGate()
    val failure = captureFailure(watcher::dispose)
    assertSame(connectionCloseFailure, failure)
    assertEquals(1, failure.suppressed.size)
    assertSame(nativeCloseFailure, failure.suppressed.single())
    assertEquals(1, connection.closeCount)
    assertEquals(1, nativeCloseCount.get())
    assertTrue(watcher.isDisposed)

    pendingRefresh.release.complete(Unit)
    yield()
    assertTrue(refreshedPaths.tryReceive().isFailure)

    watcher.dispose()
    assertEquals(1, connection.closeCount)
    assertEquals(1, nativeCloseCount.get())
  }

  fun testManifestCreatedAfterWatcherInstallationRequestsTheSharedPipeline() = runBlocking {
    val root = myFixture.tempDirFixture.findOrCreateDir("watch-create")
    val manifestPath = Path.of(root.path).resolve(".reqws/workspace.json")
    val waiter = ControlledWaiter()
    val requests = Channel<Unit>(Channel.UNLIMITED)
    val watcher = ManifestVfsWatcher(
      project = project,
      manifestPath = manifestPath,
      coroutineScope = this,
      syncRequest = ManifestSyncRequest { requests.trySend(Unit) },
      debounceWaiter = waiter,
    )

    writeAction {
      val metadata = root.createChildDirectory(this, ".reqws")
      metadata.createChildData(this, "workspace.json")
        .setBinaryContent("{}".toByteArray(StandardCharsets.UTF_8))
    }
    waiter.nextGate().release.complete(Unit)

    requests.receive()
    watcher.dispose()
  }

  fun testRelevantAfterEventRequestsTheSharedPipelineAfter350Milliseconds() = runBlocking {
    val manifest = myFixture.tempDirFixture.createFile("watch-relevant/.reqws/workspace.json", "{}")
    val waiter = ControlledWaiter()
    val requests = Channel<Unit>(Channel.UNLIMITED)
    val watcher = ManifestVfsWatcher(
      project = project,
      manifestPath = Path.of(manifest.path),
      coroutineScope = this,
      syncRequest = ManifestSyncRequest { requests.trySend(Unit) },
      debounceWaiter = waiter,
    )

    writeAction {
      manifest.setBinaryContent("{\"updated\":true}".toByteArray(StandardCharsets.UTF_8))
    }
    val gate = waiter.nextGate()
    assertEquals(LatestDebouncer.DEFAULT_DELAY_MILLIS, gate.delayMillis)
    gate.release.complete(Unit)

    requests.receive()
    watcher.dispose()
  }

  fun testExternalAtomicReplacementRequestsTheSharedPipelineWithoutManualVfsRefresh() {
    val future = AppExecutorUtil.getAppExecutorService().submit(Callable {
      runBlocking {
        val physicalRoot = Files.createTempDirectory("reqws-watch-external-")
        val manifestPath = physicalRoot.resolve(".reqws/workspace.json")
        Files.createDirectories(manifestPath.parent)
        Files.writeString(manifestPath, "{}", StandardCharsets.UTF_8)
        val replacementPath = manifestPath.parent.resolve(".workspace.json.external.tmp")
        val replacementContent = "{\"replacement\":\"external-atomic-move\"}"
        val waiter = ControlledWaiter()
        val refreshWaiter = ControlledWaiter()
        val requests = Channel<Unit>(Channel.UNLIMITED)
        val watcher = ManifestVfsWatcher(
          project = project,
          manifestPath = manifestPath,
          coroutineScope = this,
          syncRequest = ManifestSyncRequest { requests.trySend(Unit) },
          debounceWaiter = waiter,
          refreshWaiter = refreshWaiter,
          nativeWatchRegistrar = ManifestNativeWatchRegistrar { ManifestNativeWatch {} },
        )

        try {
          Files.writeString(replacementPath, replacementContent, StandardCharsets.UTF_8)
          Files.move(
            replacementPath,
            manifestPath,
            StandardCopyOption.ATOMIC_MOVE,
            StandardCopyOption.REPLACE_EXISTING,
          )
          yield()
          assertTrue(waiter.tryNextGate().isFailure)

          val refreshGate = withTimeout(10_000) { refreshWaiter.nextGate() }
          assertEquals(ManifestVfsWatcher.VFS_REFRESH_INTERVAL_MILLIS, refreshGate.delayMillis)
          refreshGate.release.complete(Unit)

          val gate = withTimeout(10_000) { waiter.nextGate() }
          assertEquals(LatestDebouncer.DEFAULT_DELAY_MILLIS, gate.delayMillis)
          gate.release.complete(Unit)

          withTimeout(10_000) { requests.receive() }
          assertTrue(requests.tryReceive().isFailure)

          val idleRefreshGate = withTimeout(10_000) { refreshWaiter.nextGate() }
          assertEquals(ManifestVfsWatcher.VFS_REFRESH_INTERVAL_MILLIS, idleRefreshGate.delayMillis)
          idleRefreshGate.release.complete(Unit)
          withTimeout(10_000) { refreshWaiter.nextGate() }

          val lateDebounceGate = withTimeoutOrNull(
            ManifestVfsWatcher.VFS_REFRESH_INTERVAL_MILLIS +
              LatestDebouncer.DEFAULT_DELAY_MILLIS,
          ) {
            waiter.nextGate()
          }
          assertNull("An unchanged pump tick must not schedule a late duplicate sync", lateDebounceGate)
          assertTrue(requests.tryReceive().isFailure)
          assertEquals(replacementContent, Files.readString(manifestPath, StandardCharsets.UTF_8))
          assertFalse(Files.exists(replacementPath))
        } finally {
          watcher.dispose()
          physicalRoot.toFile().deleteRecursively()
        }
      }
    })

    PlatformTestUtil.waitForFuture(future)
  }

  fun testExternallyCreatedMetadataDirectoryAndManifestAreDetectedByThePump() {
    val future = AppExecutorUtil.getAppExecutorService().submit(Callable {
      runBlocking {
        val physicalRoot = Files.createTempDirectory("reqws-watch-external-create-")
        val manifestPath = physicalRoot.resolve(".reqws/workspace.json")
        val waiter = ControlledWaiter()
        val refreshWaiter = ControlledWaiter()
        val requests = Channel<Unit>(Channel.UNLIMITED)
        val watcher = ManifestVfsWatcher(
          project = project,
          manifestPath = manifestPath,
          coroutineScope = this,
          syncRequest = ManifestSyncRequest { requests.trySend(Unit) },
          debounceWaiter = waiter,
          refreshWaiter = refreshWaiter,
          nativeWatchRegistrar = ManifestNativeWatchRegistrar { ManifestNativeWatch {} },
        )

        try {
          Files.createDirectories(manifestPath.parent)
          Files.writeString(manifestPath, "{}", StandardCharsets.UTF_8)
          yield()
          assertTrue(waiter.tryNextGate().isFailure)

          val refreshGate = withTimeout(10_000) { refreshWaiter.nextGate() }
          refreshGate.release.complete(Unit)
          val debounceGate = withTimeout(10_000) { waiter.nextGate() }
          assertEquals(LatestDebouncer.DEFAULT_DELAY_MILLIS, debounceGate.delayMillis)
          debounceGate.release.complete(Unit)

          withTimeout(10_000) { requests.receive() }
          assertTrue(requests.tryReceive().isFailure)
        } finally {
          watcher.dispose()
          physicalRoot.toFile().deleteRecursively()
        }
      }
    })

    PlatformTestUtil.waitForFuture(future)
  }

  fun testAtomicReplacementAndRapidContentChangesCoalesce() = runBlocking {
    val parent = myFixture.tempDirFixture.findOrCreateDir("watch-burst/.reqws")
    val manifest = myFixture.tempDirFixture.createFile("watch-burst/.reqws/workspace.json", "{}")
    val waiter = ControlledWaiter()
    val requests = Channel<Unit>(Channel.UNLIMITED)
    val watcher = ManifestVfsWatcher(
      project = project,
      manifestPath = Path.of(manifest.path),
      coroutineScope = this,
      syncRequest = ManifestSyncRequest { requests.trySend(Unit) },
      debounceWaiter = waiter,
    )
    lateinit var replacement: VirtualFile

    writeAction {
      manifest.delete(this)
      replacement = parent.createChildData(this, "workspace.json")
      replacement.setBinaryContent("{\"replacement\":true}".toByteArray(StandardCharsets.UTF_8))
    }
    waiter.nextGate().release.complete(Unit)
    requests.receive()
    assertTrue(requests.tryReceive().isFailure)

    writeAction {
      replacement.setBinaryContent("{\"revision\":1}".toByteArray(StandardCharsets.UTF_8))
      replacement.setBinaryContent("{\"revision\":2}".toByteArray(StandardCharsets.UTF_8))
      replacement.setBinaryContent("{\"revision\":3}".toByteArray(StandardCharsets.UTF_8))
    }
    waiter.nextGate().release.complete(Unit)
    requests.receive()
    yield()
    assertTrue(requests.tryReceive().isFailure)
    assertTrue(waiter.tryNextGate().isFailure)
    watcher.dispose()
  }

  fun testIgnoresUnrelatedSiblingPaths() = runBlocking {
    val manifest = myFixture.tempDirFixture.createFile("watch-ignore/.reqws/workspace.json", "{}")
    val sibling = myFixture.tempDirFixture.createFile("watch-ignore/.reqws/workspace.json.tmp", "{}")
    val waiter = ControlledWaiter()
    val requests = Channel<Unit>(Channel.UNLIMITED)
    val watcher = ManifestVfsWatcher(
      project = project,
      manifestPath = Path.of(manifest.path),
      coroutineScope = this,
      syncRequest = ManifestSyncRequest { requests.trySend(Unit) },
      debounceWaiter = waiter,
    )

    writeAction {
      sibling.setBinaryContent("changed".toByteArray(StandardCharsets.UTF_8))
    }
    yield()

    assertTrue(waiter.tryNextGate().isFailure)
    assertTrue(requests.tryReceive().isFailure)
    watcher.dispose()
  }

  fun testDisposeCancelsPendingWorkDisconnectsAndRejectsFutureCallbacks() = runBlocking {
    val manifest = myFixture.tempDirFixture.createFile("watch-dispose/.reqws/workspace.json", "{}")
    val waiter = ControlledWaiter()
    val requests = Channel<Unit>(Channel.UNLIMITED)
    val watcher = ManifestVfsWatcher(
      project = project,
      manifestPath = Path.of(manifest.path),
      coroutineScope = this,
      syncRequest = ManifestSyncRequest { requests.trySend(Unit) },
      debounceWaiter = waiter,
    )

    writeAction {
      manifest.setBinaryContent("pending".toByteArray(StandardCharsets.UTF_8))
    }
    val pendingGate = waiter.nextGate()
    watcher.dispose()
    pendingGate.release.complete(Unit)
    writeAction {
      manifest.setBinaryContent("after-dispose".toByteArray(StandardCharsets.UTF_8))
    }
    yield()

    assertTrue(watcher.isDisposed)
    assertTrue(requests.tryReceive().isFailure)
    assertTrue(waiter.tryNextGate().isFailure)
  }

  private suspend fun assertRefreshCancellationStopsPump(
    expectedCancellation: Throwable,
    traceEnabled: Boolean,
  ) {
    val root = myFixture.tempDirFixture.findOrCreateDir("watch-refresh-cancellation")
    val ownerJob = SupervisorJob()
    val escapedFailures = CopyOnWriteArrayList<Throwable>()
    val scope = CoroutineScope(
      ownerJob + Dispatchers.IO + CoroutineExceptionHandler { _, failure -> escapedFailures += failure },
    )
    val refreshWaiter = ControlledWaiter()
    val attempts = AtomicInteger()
    val failures = CopyOnWriteArrayList<Throwable>()
    val traceLines = CopyOnWriteArrayList<String>()
    val watcher = ManifestVfsWatcher(
      project = project,
      manifestPath = Path.of(root.path).resolve(".reqws/workspace.json"),
      coroutineScope = scope,
      syncRequest = ManifestSyncRequest {},
      onFailure = failures::add,
      nativeWatchRegistrar = ManifestNativeWatchRegistrar { ManifestNativeWatch {} },
      refreshWaiter = refreshWaiter,
      vfsRefreshFactory = ManifestVfsRefreshFactory {
        ManifestVfsRefresh {
          attempts.incrementAndGet()
          throw expectedCancellation
        }
      },
      vfsEventConnector = ManifestVfsEventConnector { _, _ -> RecordingManifestVfsEventConnection() },
      trace = ReqwsSyncTrace.testing(enabled = traceEnabled, sink = traceLines::add),
    )
    try {
      val firstGate = withTimeout(5_000) { refreshWaiter.nextGate() }
      // No VFS events were submitted, so the refresh pump is the only child job.
      val pump = ownerJob.children.single()
      val completion = CompletableDeferred<Throwable?>()
      pump.invokeOnCompletion { completion.complete(it) }
      firstGate.release.complete(Unit)
      assertSame(expectedCancellation, withTimeout(5_000) { completion.await() })
      assertTrue(pump.isCompleted)
      assertTrue(pump.isCancelled)
      assertTrue(ownerJob.isActive)
      assertEquals(1, attempts.get())
      assertTrue(failures.isEmpty())
      assertTrue(refreshWaiter.tryNextGate().isFailure)
      if (expectedCancellation is CancellationException) {
        assertTrue(escapedFailures.isEmpty())
      } else {
        assertSame(expectedCancellation, escapedFailures.single())
      }
      if (traceEnabled) {
        assertEquals(1, traceEvents(traceLines, SyncTraceEvent.WATCH_REFRESH_START).size)
        assertTrue(
          traceEvents(traceLines, SyncTraceEvent.WATCH_REFRESH_END).single().contains("outcome=CANCELLED"),
        )
      } else {
        assertTrue(traceLines.isEmpty())
      }
    } finally {
      watcher.dispose()
      ownerJob.cancel()
    }
  }

  private fun writeAction(action: () -> Unit) {
    ApplicationManager.getApplication().runWriteAction(action)
  }

  private fun traceEvents(lines: List<String>, event: SyncTraceEvent): List<String> =
    lines.filter { it.contains(" event=${event.name} ") }

  private fun captureFailure(action: () -> Unit): Throwable {
    try {
      action()
    } catch (failure: Throwable) {
      return failure
    }
    throw AssertionError("Expected the action to fail")
  }

  private data class Gate(
    val delayMillis: Long,
    val release: CompletableDeferred<Unit>,
  )

  private class ControlledWaiter : DebounceWaiter {
    private val gates = Channel<Gate>(Channel.UNLIMITED)

    override suspend fun await(delayMillis: Long) {
      val gate = Gate(delayMillis, CompletableDeferred())
      gates.send(gate)
      gate.release.await()
    }

    suspend fun nextGate(): Gate = gates.receive()

    fun tryNextGate() = gates.tryReceive()
  }

  private class RecordingManifestVfsEventConnection(
    private val subscribeFailure: Throwable? = null,
    private val closeFailure: Throwable? = null,
  ) : ManifestVfsEventConnection {
    var subscribeCount = 0
      private set
    var closeCount = 0
      private set
    lateinit var listener: BulkFileListener
      private set

    override fun subscribe(listener: BulkFileListener) {
      subscribeCount += 1
      subscribeFailure?.let { throw it }
      this.listener = listener
    }

    override fun close() {
      closeCount += 1
      closeFailure?.let { throw it }
    }
  }
}
