package com.reqws.goland.projectmodel

import com.intellij.openapi.progress.ProcessCanceledException
import com.reqws.goland.diagnostics.ReqwsSyncTrace
import com.reqws.goland.diagnostics.SyncTraceEvent
import com.reqws.goland.diagnostics.traceRecords
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ReqwsGoModulesSynchronizerTest {
  private val temporaryRoots = mutableListOf<Path>()

  @After
  fun tearDown() {
    temporaryRoots.asReversed().forEach { root -> root.toFile().deleteRecursively() }
  }

  @Test
  fun `does not notify roots when active and excluded module roots already converge`() = runBlocking {
    val root = temporaryRoot()
    val active = goRepository(root, "active")
    val excluded = goRepository(root, "excluded")
    val notifier = RecordingNotifier { _ -> }
    val synchronizer = synchronizer(
      registryRoots = { listOf(active) },
      notifier = notifier,
    )

    synchronizer.synchronize(
      moduleName = "workspace",
      activeRepositoryPaths = listOf(active),
      excludedPaths = listOf(excluded),
    )

    assertEquals(0, notifier.callCount)
  }

  @Test
  fun `notifies roots for an active module missing after a no-op reconciliation`() = runBlocking {
    val root = temporaryRoot()
    val repoA = goRepository(root, "repo-a")
    val repoC = goRepository(root, "repo-c")
    val registryRoots = linkedSetOf(repoA)
    val notifier = RecordingNotifier { _ ->
      registryRoots.add(repoC)
    }
    val synchronizer = synchronizer(
      registryRoots = { registryRoots },
      notifier = notifier,
    )

    synchronizer.synchronize(
      moduleName = "workspace",
      activeRepositoryPaths = listOf(repoA, repoC),
      excludedPaths = emptyList(),
    )

    assertEquals(1, notifier.callCount)
    assertEquals(setOf(repoA, repoC), registryRoots)
  }

  @Test
  fun `does not repeat the roots notification while an asynchronous refresh converges`() = runBlocking {
    val root = temporaryRoot()
    val repoA = goRepository(root, "repo-a")
    val repoC = goRepository(root, "repo-c")
    val registryRoots = linkedSetOf(repoA)
    val notifier = RecordingNotifier { _ -> }
    val waits = mutableListOf<Long>()
    val synchronizer = synchronizer(
      registryRoots = { registryRoots },
      notifier = notifier,
      maxWaitCycles = 3,
      waiter = ReqwsGoModulesSyncWaiter {
        waits.add(it)
        if (waits.size == 2) registryRoots.add(repoC)
      },
    )

    synchronizer.synchronize(
      moduleName = "workspace",
      activeRepositoryPaths = listOf(repoA, repoC),
      excludedPaths = emptyList(),
    )

    assertEquals(1, notifier.callCount)
    assertEquals(listOf(1L, 1L), waits)
  }

  @Test
  fun `retries when the notifier final gate initially declines the roots event`() = runBlocking {
    val root = temporaryRoot()
    val active = goRepository(root, "active")
    val registryRoots = linkedSetOf<Path>()
    var notificationAttempts = 0
    val synchronizer = ReqwsGoModulesSynchronizer(
      registryView = ReqwsGoModulesRegistryView { registryRoots },
      rootsChangeNotifier = ReqwsProjectRootsChangeNotifier {
        notificationAttempts += 1
        if (notificationAttempts == 2) registryRoots.add(active)
        notificationAttempts == 2
      },
      isProjectDisposed = { false },
      isTrusted = { true },
      maxWaitCycles = 2,
      retryDelayMillis = 1L,
      waiter = ReqwsGoModulesSyncWaiter {},
    )

    synchronizer.synchronize(
      moduleName = "workspace",
      activeRepositoryPaths = listOf(active),
      excludedPaths = emptyList(),
    )

    assertEquals(2, notificationAttempts)
  }

  @Test
  fun `waits for an asynchronously added active module after one refresh request`() = runBlocking {
    val root = temporaryRoot()
    val repoA = goRepository(root, "repo-a")
    val repoC = goRepository(root, "repo-c")
    val registryRoots = linkedSetOf(repoA)
    val notifier = RecordingNotifier { _ -> }
    var waitCount = 0
    val synchronizer = synchronizer(
      registryRoots = { registryRoots },
      notifier = notifier,
      maxWaitCycles = 3,
      waiter = ReqwsGoModulesSyncWaiter {
        waitCount += 1
        if (waitCount == 2) registryRoots.add(repoC)
      },
    )

    synchronizer.synchronize(
      moduleName = "workspace",
      activeRepositoryPaths = listOf(repoA, repoC),
      excludedPaths = emptyList(),
    )

    assertEquals(1, notifier.callCount)
    assertEquals(2, waitCount)
  }

  @Test
  fun `waits for an excluded module to leave the registry`() = runBlocking {
    val root = temporaryRoot()
    val active = goRepository(root, "active")
    val excluded = goRepository(root, "excluded")
    val registryRoots = linkedSetOf(active, excluded)
    val notifier = RecordingNotifier { _ ->
      registryRoots.remove(excluded)
    }
    val synchronizer = synchronizer(
      registryRoots = { registryRoots },
      notifier = notifier,
    )

    synchronizer.synchronize(
      moduleName = "workspace",
      activeRepositoryPaths = listOf(active),
      excludedPaths = listOf(excluded),
    )

    assertEquals(1, notifier.callCount)
    assertEquals(setOf(active), registryRoots)
  }

  @Test
  fun `fails when the Go Modules registry never converges`() {
    val root = temporaryRoot()
    val active = goRepository(root, "active")
    val notifier = RecordingNotifier { _ -> }
    val waits = mutableListOf<Long>()
    val synchronizer = synchronizer(
      registryRoots = { emptyList() },
      notifier = notifier,
      maxWaitCycles = 2,
      waiter = ReqwsGoModulesSyncWaiter(waits::add),
    )

    val failure = assertThrows(ProjectModelApplyException::class.java) {
      runBlocking {
        synchronizer.synchronize(
          moduleName = "workspace",
          activeRepositoryPaths = listOf(active),
          excludedPaths = emptyList(),
        )
      }
    }

    assertEquals(ProjectModelErrorCode.GO_MODULES_REGISTRY_NOT_CONVERGED, failure.code)
    assertEquals(1, notifier.callCount)
    assertEquals(listOf(1L, 1L), waits)
  }

  @Test
  fun `bounded project-model follow-up never publishes another roots notification`() {
    val root = temporaryRoot()
    val active = goRepository(root, "active")
    val notifier = RecordingNotifier { _ -> error("notifier must not run") }
    val synchronizer = synchronizer(
      registryRoots = { emptyList() },
      notifier = notifier,
      maxWaitCycles = 1,
    )

    val failure = assertThrows(ProjectModelApplyException::class.java) {
      runBlocking {
        synchronizer.synchronize(
          moduleName = "workspace",
          activeRepositoryPaths = listOf(active),
          excludedPaths = emptyList(),
          allowRootsChangeNotification = false,
        )
      }
    }

    assertEquals(ProjectModelErrorCode.GO_MODULES_REGISTRY_NOT_CONVERGED, failure.code)
    assertEquals(0, notifier.callCount)
  }

  @Test
  fun `fails immediately without observing the registry after project disposal`() {
    var registryReads = 0
    val notifier = RecordingNotifier { _ -> error("notifier must not run") }
    val synchronizer = ReqwsGoModulesSynchronizer(
      registryView = ReqwsGoModulesRegistryView {
        registryReads += 1
        emptyList()
      },
      rootsChangeNotifier = notifier,
      isProjectDisposed = { true },
      isTrusted = { true },
      maxWaitCycles = 1,
      retryDelayMillis = 1L,
      waiter = ReqwsGoModulesSyncWaiter { error("waiter must not run") },
    )

    val failure = assertThrows(ProjectModelApplyException::class.java) {
      runBlocking {
        synchronizer.synchronize(
          moduleName = "workspace",
          activeRepositoryPaths = emptyList(),
          excludedPaths = emptyList(),
        )
      }
    }

    assertEquals(ProjectModelErrorCode.PROJECT_DISPOSED, failure.code)
    assertEquals(0, registryReads)
    assertEquals(0, notifier.callCount)
  }

  @Test
  fun `does not publish a roots event after the project becomes untrusted`() {
    val root = temporaryRoot()
    val active = goRepository(root, "active")
    val notifier = RecordingNotifier { _ -> error("notifier must not run") }
    val synchronizer = ReqwsGoModulesSynchronizer(
      registryView = ReqwsGoModulesRegistryView { emptyList() },
      rootsChangeNotifier = notifier,
      isProjectDisposed = { false },
      isTrusted = { false },
      maxWaitCycles = 1,
      retryDelayMillis = 1L,
      waiter = ReqwsGoModulesSyncWaiter { error("waiter must not run") },
    )

    val failure = assertThrows(ProjectModelApplyException::class.java) {
      runBlocking {
        synchronizer.synchronize(
          moduleName = "workspace",
          activeRepositoryPaths = listOf(active),
          excludedPaths = emptyList(),
        )
      }
    }

    assertEquals(ProjectModelErrorCode.UNTRUSTED_PROJECT, failure.code)
    assertEquals(0, notifier.callCount)
  }

  @Test
  fun `propagates waiter cancellation without converting it to a model failure`() {
    val root = temporaryRoot()
    val active = goRepository(root, "active")
    val cancellation = CancellationException("cancel Go registry wait")
    val synchronizer = synchronizer(
      registryRoots = { emptyList() },
      notifier = RecordingNotifier { _ -> },
      maxWaitCycles = 2,
      waiter = ReqwsGoModulesSyncWaiter { throw cancellation },
    )

    val thrown = assertThrows(CancellationException::class.java) {
      runBlocking {
        synchronizer.synchronize(
          moduleName = "workspace",
          activeRepositoryPaths = listOf(active),
          excludedPaths = emptyList(),
        )
      }
    }

    assertSame(cancellation, thrown)
  }

  @Test
  fun `requires only regular nofollow top-level go mod files`() = runBlocking {
    val root = temporaryRoot()
    val missing = Files.createDirectories(root.resolve("missing"))
    val directory = Files.createDirectories(root.resolve("directory"))
    Files.createDirectories(directory.resolve("go.mod"))
    val symlink = Files.createDirectories(root.resolve("symlink"))
    val target = Files.writeString(root.resolve("go-mod-target"), "module example.com/target\n")
    Files.createSymbolicLink(symlink.resolve("go.mod"), target)
    val notifier = RecordingNotifier { _ -> }
    val synchronizer = synchronizer(
      registryRoots = { emptyList() },
      notifier = notifier,
    )

    synchronizer.synchronize(
      moduleName = "workspace",
      activeRepositoryPaths = listOf(missing, directory, symlink),
      excludedPaths = emptyList(),
    )

    assertEquals(0, notifier.callCount)
  }

  @Test
  fun `trace counts the first successful registry read with zero wait and notification`() = runBlocking {
    val active = goRepository(temporaryRoot(), "private-repository-name")
    val lines = mutableListOf<String>()
    val trace = ReqwsSyncTrace.testing(sink = lines::add)
    withContext(trace.workerContext()) {
      trace.withAttempt(9, 19) {
        ReqwsGoModulesSynchronizer(
          registryView = ReqwsGoModulesRegistryView { listOf(active) },
          rootsChangeNotifier = ReqwsProjectRootsChangeNotifier { error("no notification expected") },
          isProjectDisposed = { false }, isTrusted = { true }, trace = trace,
        ).synchronize("private-module-name", listOf(active), emptyList(), true)
      }
    }
    assertRegistryTrace(lines, "SUCCESS", reads = 1, waits = 0, attempts = 0, rejected = 0, published = 0)
    assertTrue(traceRecords(lines, SyncTraceEvent.REGISTRY_END).all { it["request_id"] == "9" && it["source_id"] == "19" })
    assertTrue(lines.none { it.contains("private-") || it.contains(active.toString()) })
  }

  @Test
  fun `trace separates rejected notification attempts from publication and convergence reads`() = runBlocking {
    val active = goRepository(temporaryRoot(), "active")
    val lines = mutableListOf<String>()
    var reads = 0
    var attempts = 0
    ReqwsGoModulesSynchronizer(
      registryView = ReqwsGoModulesRegistryView { if (++reads == 4) listOf(active) else emptyList() },
      rootsChangeNotifier = ReqwsProjectRootsChangeNotifier { ++attempts == 2 },
      isProjectDisposed = { false }, isTrusted = { true }, maxWaitCycles = 2,
      waiter = ReqwsGoModulesSyncWaiter {}, trace = ReqwsSyncTrace.testing(sink = lines::add),
    ).synchronize("workspace", listOf(active), emptyList(), true)
    assertEquals(4, reads)
    assertEquals(2, attempts)
    assertRegistryTrace(lines, "SUCCESS", reads = 4, waits = 1, attempts = 2, rejected = 1, published = 1)
    assertEquals(listOf("0", "1"), traceRecords(lines, SyncTraceEvent.ROOTS_NOTIFICATION).map { it["accepted"] })
  }

  @Test
  fun `trace records the bounded nonconverged path without unauthorized notifications`() {
    val active = goRepository(temporaryRoot(), "active")
    val lines = mutableListOf<String>()
    val synchronizer = ReqwsGoModulesSynchronizer(
      registryView = ReqwsGoModulesRegistryView { emptyList() },
      rootsChangeNotifier = ReqwsProjectRootsChangeNotifier { error("notification prohibited") },
      isProjectDisposed = { false }, isTrusted = { true }, maxWaitCycles = 2,
      waiter = ReqwsGoModulesSyncWaiter {}, trace = ReqwsSyncTrace.testing(sink = lines::add),
    )
    val failure = assertThrows(ProjectModelApplyException::class.java) {
      runBlocking { synchronizer.synchronize("workspace", listOf(active), emptyList(), false) }
    }
    assertEquals(ProjectModelErrorCode.GO_MODULES_REGISTRY_NOT_CONVERGED, failure.code)
    assertRegistryTrace(lines, "NOT_CONVERGED", reads = 3, waits = 2, attempts = 0, rejected = 0, published = 0)
    assertEquals("0", traceRecords(lines, SyncTraceEvent.REGISTRY_END).single()["allow_notification"])
  }

  @Test
  fun `trace preserves registry notifier and waiter failures with exact attempted work counts`() {
    val active = goRepository(temporaryRoot(), "active")
    for (stage in listOf("read", "notify", "wait")) {
      for (failure in listOf(IllegalStateException("failure"), ProcessCanceledException(), CancellationException("cancel"))) {
        val lines = mutableListOf<String>()
        val synchronizer = ReqwsGoModulesSynchronizer(
          registryView = ReqwsGoModulesRegistryView { if (stage == "read") throw failure else emptyList() },
          rootsChangeNotifier = ReqwsProjectRootsChangeNotifier { if (stage == "notify") throw failure else true },
          isProjectDisposed = { false }, isTrusted = { true },
          waiter = ReqwsGoModulesSyncWaiter { throw failure }, trace = ReqwsSyncTrace.testing(sink = lines::add),
        )
        val thrown = assertThrows(failure.javaClass) {
          runBlocking { synchronizer.synchronize("workspace", listOf(active), emptyList(), true) }
        }
        assertSame(failure, thrown)
        assertRegistryTrace(
          lines, if (failure is ProcessCanceledException || failure is CancellationException) "CANCELLED" else "FAILED",
          reads = if (stage == "wait") 2 else 1, waits = if (stage == "wait") 1 else 0,
          attempts = if (stage == "read") 0 else 1, rejected = 0, published = if (stage == "wait") 1 else 0,
        )
      }
    }
  }

  private fun assertRegistryTrace(
    lines: List<String>, outcome: String, reads: Int, waits: Int, attempts: Int, rejected: Int, published: Int,
  ) {
    val start = traceRecords(lines, SyncTraceEvent.REGISTRY_START).single()
    val end = traceRecords(lines, SyncTraceEvent.REGISTRY_END).single()
    assertEquals(start["span_id"], end["span_id"])
    assertEquals(outcome, end["outcome"])
    assertEquals(reads.toString(), end["reads"])
    assertEquals(waits.toString(), end["waits"])
    assertEquals(attempts.toString(), end["notification_attempts"])
    assertEquals(rejected.toString(), end["notification_rejected"])
    assertEquals(published.toString(), end["notifications"])
    assertTrue(requireNotNull(end["elapsed_nanos"]).toLong() >= 0)
  }

  private fun synchronizer(
    registryRoots: () -> Collection<Path>,
    notifier: RecordingNotifier,
    maxWaitCycles: Int = 2,
    waiter: ReqwsGoModulesSyncWaiter = ReqwsGoModulesSyncWaiter {},
  ): ReqwsGoModulesSynchronizer = ReqwsGoModulesSynchronizer(
    registryView = ReqwsGoModulesRegistryView { registryRoots() },
    rootsChangeNotifier = notifier,
    isProjectDisposed = { false },
    isTrusted = { true },
    maxWaitCycles = maxWaitCycles,
    retryDelayMillis = 1L,
    waiter = waiter,
  )

  private fun temporaryRoot(): Path = Files.createTempDirectory("reqws-go-modules-sync-")
    .also(temporaryRoots::add)

  private fun goRepository(root: Path, name: String): Path =
    Files.createDirectories(root.resolve(name)).also { repository ->
      Files.writeString(repository.resolve("go.mod"), "module example.com/$name\n")
    }

  private class RecordingNotifier(
    private val action: (Int) -> Unit,
  ) : ReqwsProjectRootsChangeNotifier {
    var callCount = 0
      private set

    override suspend fun notifyRootsChanged(): Boolean {
      callCount += 1
      action(callCount)
      return true
    }
  }
}
