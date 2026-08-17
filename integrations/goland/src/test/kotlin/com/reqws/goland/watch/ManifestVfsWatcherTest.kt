package com.reqws.goland.watch

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.reqws.goland.sync.DebounceWaiter
import com.reqws.goland.sync.LatestDebouncer
import java.nio.charset.StandardCharsets
import java.nio.file.Path
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield

class ManifestVfsWatcherTest : BasePlatformTestCase() {
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

  private fun writeAction(action: () -> Unit) {
    ApplicationManager.getApplication().runWriteAction(action)
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
}
