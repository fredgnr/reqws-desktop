package com.reqws.goland.projectmodel

import java.nio.file.Files
import java.nio.file.Path
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
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
