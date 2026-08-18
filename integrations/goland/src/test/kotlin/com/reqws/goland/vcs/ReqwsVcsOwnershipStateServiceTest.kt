package com.reqws.goland.vcs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.channels.FileChannel
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class ReqwsVcsOwnershipStateServiceTest {
  @Rule
  @JvmField
  val temporaryFolder = TemporaryFolder()

  @Test
  fun `atomically persists workspace-bound state and only the writer session retains created`() {
    val root = workspaceRoot()
    Files.createDirectory(root.resolve("repo-a"))
    Files.createDirectory(root.resolve("repo-b"))
    Files.createDirectory(root.resolve("repo-c"))
    val service = service(EPOCH_A)
    val expected = VcsMappingOwnershipState(
      stableMappings = listOf(VcsMappingOwnership("repo-a", VcsMappingOwnershipKind.CREATED)),
      pendingAdds = listOf(VcsMappingPendingOwnership("repo-b", TOKEN_A)),
      pendingRemovals = listOf(VcsMappingPendingOwnership("repo-c", TOKEN_B)),
    )

    persist(service, root, expected)
    val sameSession = service.readForProject(root, WORKSPACE_ID)
    val foreignSession = service(EPOCH_B).readForProject(root, WORKSPACE_ID)

    assertEquals(expected.stableMappings, sameSession.ownership)
    assertEquals(expected.pendingAdds, sameSession.pendingAdds)
    assertEquals(expected.pendingRemovals, sameSession.pendingRemovals)
    assertEquals(
      listOf(VcsMappingOwnership("repo-a", VcsMappingOwnershipKind.BORROWED)),
      foreignSession.ownership,
    )
    assertTrue(foreignSession.diagnostics.isEmpty())
    val json = Files.readString(stateFile(root), StandardCharsets.UTF_8)
    assertTrue(json.contains("\"stateVersion\":3"))
    assertTrue(json.contains("\"workspaceId\":\"$WORKSPACE_ID\""))
    assertTrue(json.contains("\"writerEpoch\":\"$EPOCH_A\""))
    assertTrue(json.contains("\"pendingRemovals\""))
    assertFalse(json.contains(root.toString()))
    assertFalse(json.contains("http"))
  }

  @Test
  fun `cold pending tombstones never appear in deletion-authorizing ownership`() {
    val root = workspaceRoot()
    Files.createDirectory(root.resolve("repo-a"))
    Files.createDirectory(root.resolve("repo-b"))
    val service = service(EPOCH_A)
    persist(
      service,
      root,
      VcsMappingOwnershipState(
        stableMappings = emptyList(),
        pendingAdds = listOf(VcsMappingPendingOwnership("repo-a", TOKEN_A)),
        pendingRemovals = listOf(VcsMappingPendingOwnership("repo-b", TOKEN_B)),
      ),
    )

    val loaded = service(EPOCH_B).readForProject(root, WORKSPACE_ID)

    assertTrue(loaded.ownership.isEmpty())
    assertEquals(listOf("repo-a"), loaded.pendingAdds.map { it.relativeDirectory })
    assertEquals(listOf("repo-b"), loaded.pendingRemovals.map { it.relativeDirectory })
  }

  @Test
  fun `malformed unsupported and cross-phase duplicate state authorizes no mapping`() {
    val root = workspaceRoot()
    Files.createDirectory(root.resolve("repo-a"))
    val writer = service(EPOCH_A)
    persist(
      writer,
      root,
      VcsMappingOwnershipState(
        stableMappings = listOf(VcsMappingOwnership("repo-a", VcsMappingOwnershipKind.CREATED)),
      ),
    )
    val validJson = Files.readString(stateFile(root), StandardCharsets.UTF_8)
    Files.writeString(
      stateFile(root),
      validJson.replace(
        "\"pendingRemovals\":[]",
        "\"pendingRemovals\":[{\"relativeDirectory\":\"repo-a\",\"operationToken\":\"$TOKEN_A\"}]",
      ),
      StandardCharsets.UTF_8,
    )

    val duplicate = service(EPOCH_B).readForProject(root, WORKSPACE_ID)
    assertTrue(duplicate.ownership.isEmpty())
    assertTrue(duplicate.pendingRemovals.isEmpty())
    assertEquals(VcsMappingDiagnosticCode.OWNERSHIP_CONFLICT, duplicate.diagnostics.single().code)

    Files.writeString(
      stateFile(root),
      validJson.replace("\"stateVersion\":3", "\"stateVersion\":1"),
      StandardCharsets.UTF_8,
    )
    val unsupported = service(EPOCH_B).readForProject(root, WORKSPACE_ID)
    assertTrue(unsupported.ownership.isEmpty())
    assertEquals(VcsMappingDiagnosticCode.OWNERSHIP_CONFLICT, unsupported.diagnostics.single().code)

    Files.write(stateFile(root), byteArrayOf(0xc3.toByte(), 0x28))
    val invalidUtf8 = service(EPOCH_B).readForProject(root, WORKSPACE_ID)
    assertTrue(invalidUtf8.ownership.isEmpty())
    assertEquals(VcsMappingDiagnosticCode.OWNERSHIP_CONFLICT, invalidUtf8.diagnostics.single().code)
  }

  @Test
  fun `absolute dotted symlink and invalid token states fail closed`() {
    val root = workspaceRoot()
    val outside = temporaryFolder.newFolder("outside").toPath().toRealPath()
    Files.createSymbolicLink(root.resolve("escape"), outside)
    val service = service(EPOCH_A)
    val loaded = service.readForProject(root, WORKSPACE_ID)

    listOf(
      VcsMappingOwnershipState(
        stableMappings = listOf(VcsMappingOwnership(outside.toString(), VcsMappingOwnershipKind.CREATED)),
      ),
      VcsMappingOwnershipState(
        stableMappings = listOf(VcsMappingOwnership("repo/.", VcsMappingOwnershipKind.CREATED)),
      ),
      VcsMappingOwnershipState(
        stableMappings = listOf(VcsMappingOwnership("escape", VcsMappingOwnershipKind.CREATED)),
      ),
      VcsMappingOwnershipState(
        stableMappings = emptyList(),
        pendingAdds = listOf(VcsMappingPendingOwnership("repo-a", "bad-token")),
      ),
    ).forEach { state ->
      assertThrows(IllegalArgumentException::class.java) {
        service.prepareReplacementForProject(
          root,
          loaded.binding,
          loaded.version,
          state,
        )
      }
    }
  }

  @Test
  fun `unicode and escaped relative directories survive strict file readback`() {
    val root = workspaceRoot()
    val relative = "仓库-\\-\"quoted\""
    Files.createDirectory(root.resolve(relative))
    val service = service(EPOCH_A)
    persist(
      service,
      root,
      VcsMappingOwnershipState(
        stableMappings = listOf(VcsMappingOwnership(relative, VcsMappingOwnershipKind.BORROWED)),
      ),
    )

    assertEquals(
      relative,
      service.readForProject(root, WORKSPACE_ID).ownership.single().relativeDirectory,
    )
  }

  @Test
  fun `legacy read is side effect free and migration publishes only through a gated commit`() {
    val root = workspaceRoot()
    Files.createDirectory(root.resolve("repo-a"))
    val service = service(EPOCH_A)
    service.loadState(
      ReqwsVcsOwnershipStateService.LegacyPersistedState(
        managedMappings = mutableListOf(
          ReqwsVcsOwnershipStateService.LegacyPersistedMapping("repo-a", "CREATED"),
        ),
      ),
    )

    val loaded = service.readForProject(root, WORKSPACE_ID)

    assertEquals(
      listOf(VcsMappingOwnership("repo-a", VcsMappingOwnershipKind.BORROWED)),
      loaded.ownership,
    )
    assertFalse(Files.exists(stateFile(root)))
    assertEquals(1, service.state.managedMappings.size)

    service.recorderForProject(root, WORKSPACE_ID, loaded)
      .prepare(VcsMappingOwnershipState(loaded.ownership))
      .persistAndVerify()

    assertTrue(Files.isRegularFile(stateFile(root)))
    assertTrue(service.state.managedMappings.isEmpty())
    assertEquals(loaded.ownership, service.readForProject(root, WORKSPACE_ID).ownership)
  }

  @Test
  fun `legacy atomic v2 is read only then bound and demoted by a gated v3 commit`() {
    val root = workspaceRoot()
    Files.createDirectory(root.resolve("repo-a"))
    val seed = service(EPOCH_A)
    persist(
      seed,
      root,
      VcsMappingOwnershipState(
        stableMappings = listOf(VcsMappingOwnership("repo-a", VcsMappingOwnershipKind.CREATED)),
      ),
    )
    val currentJson = Files.readString(stateFile(root), StandardCharsets.UTF_8)
    val fingerprint = requireNotNull(
      Regex("\\\"workspaceRootFingerprint\\\":\\\"([0-9a-f]{64})\\\"")
        .find(currentJson),
    ).groupValues[1]
    val legacyJson = """{"stateVersion":2,"generation":7,"workspaceRootFingerprint":"$fingerprint","stableMappings":[{"relativeDirectory":"repo-a","kind":"CREATED"}],"pendingAdds":[],"pendingRemovals":[]}"""
    Files.writeString(stateFile(root), legacyJson, StandardCharsets.UTF_8)
    val migrator = service(EPOCH_B)

    val loaded = migrator.readForProject(root, WORKSPACE_ID)

    assertEquals(
      listOf(VcsMappingOwnership("repo-a", VcsMappingOwnershipKind.BORROWED)),
      loaded.ownership,
    )
    assertEquals(legacyJson, Files.readString(stateFile(root), StandardCharsets.UTF_8))
    migrator.recorderForProject(root, WORKSPACE_ID, loaded)
      .prepare(VcsMappingOwnershipState(loaded.ownership))
      .persistAndVerify()
    val migratedJson = Files.readString(stateFile(root), StandardCharsets.UTF_8)
    assertTrue(migratedJson.contains("\"stateVersion\":3"))
    assertTrue(migratedJson.contains("\"workspaceId\":\"$WORKSPACE_ID\""))
    assertTrue(migratedJson.contains("\"kind\":\"BORROWED\""))
  }

  @Test
  fun `legacy migration writes nothing when trust or dispose flips after locked decode`() {
    listOf(
      VcsMappingApplyErrorCode.SAFE_MODE_BLOCKED,
      VcsMappingApplyErrorCode.PROJECT_DISPOSED,
    ).forEachIndexed { index, blockedCode ->
      val root = workspaceRoot()
      Files.createDirectory(root.resolve("repo-$index"))
      val service = service(EPOCH_A)
      service.loadState(
        ReqwsVcsOwnershipStateService.LegacyPersistedState(
          managedMappings = mutableListOf(
            ReqwsVcsOwnershipStateService.LegacyPersistedMapping("repo-$index", "CREATED"),
          ),
        ),
      )
      val loaded = service.readForProject(root, WORKSPACE_ID)
      var gateCalls = 0
      val recorder = service.recorderForProject(
        root,
        WORKSPACE_ID,
        loaded,
        mutationGate = {
          gateCalls += 1
          if (gateCalls >= 3) blockedCode else null
        },
      )

      val failure = assertThrows(VcsOwnershipMutationBlockedException::class.java) {
        recorder.prepare(VcsMappingOwnershipState(loaded.ownership)).persistAndVerify()
      }

      assertEquals(blockedCode, failure.code)
      assertTrue(gateCalls >= 3)
      assertFalse(Files.exists(stateFile(root)))
      assertEquals(1, service.state.managedMappings.size)
    }
  }

  @Test
  fun `post-write gate does not deny the committed generation or clear the legacy mirror`() {
    val root = workspaceRoot()
    Files.createDirectory(root.resolve("repo-a"))
    val service = service(EPOCH_A)
    service.loadState(
      ReqwsVcsOwnershipStateService.LegacyPersistedState(
        managedMappings = mutableListOf(
          ReqwsVcsOwnershipStateService.LegacyPersistedMapping("repo-a", "CREATED"),
        ),
      ),
    )
    val loaded = service.readForProject(root, WORKSPACE_ID)
    var gateCalls = 0
    val recorder = service.recorderForProject(
      root,
      WORKSPACE_ID,
      loaded,
      mutationGate = {
        gateCalls += 1
        if (gateCalls == 4) VcsMappingApplyErrorCode.SAFE_MODE_BLOCKED else null
      },
    )
    val borrowed = VcsMappingOwnershipState(loaded.ownership)

    recorder.prepare(borrowed).persistAndVerify()
    recorder.prepare(borrowed).persistAndVerify()

    assertTrue(Files.isRegularFile(stateFile(root)))
    assertEquals(1, service.state.managedMappings.size)
    assertEquals(borrowed.stableMappings, service.readForProject(root, WORKSPACE_ID).ownership)
  }

  @Test
  fun `same root with a different workspace id fails closed and cannot overwrite binding`() {
    val root = workspaceRoot()
    Files.createDirectory(root.resolve("repo-a"))
    val writer = service(EPOCH_A)
    persist(
      writer,
      root,
      VcsMappingOwnershipState(
        stableMappings = listOf(VcsMappingOwnership("repo-a", VcsMappingOwnershipKind.CREATED)),
      ),
    )
    val original = Files.readAllBytes(stateFile(root))
    val replacementWorkspaceId = "ws_replacement"
    val replacement = service(EPOCH_B)

    val loaded = replacement.readForProject(root, replacementWorkspaceId)

    assertTrue(loaded.ownership.isEmpty())
    assertEquals(VcsMappingDiagnosticCode.OWNERSHIP_CONFLICT, loaded.diagnostics.single().code)
    assertThrows(IllegalStateException::class.java) {
      replacement.recorderForProject(root, replacementWorkspaceId, loaded)
        .prepare(VcsMappingOwnershipState(emptyList()))
        .persistAndVerify()
    }
    assertTrue(original.contentEquals(Files.readAllBytes(stateFile(root))))
  }

  @Test
  fun `copied atomic ownership file cannot authorize mappings in another workspace root`() {
    val firstRoot = workspaceRoot()
    val secondRoot = workspaceRoot()
    Files.createDirectory(firstRoot.resolve("repo-a"))
    Files.createDirectory(secondRoot.resolve("repo-a"))
    val service = service(EPOCH_A)
    persist(
      service,
      firstRoot,
      VcsMappingOwnershipState(
        stableMappings = listOf(VcsMappingOwnership("repo-a", VcsMappingOwnershipKind.CREATED)),
      ),
    )
    Files.copy(stateFile(firstRoot), stateFile(secondRoot))

    val copied = service(EPOCH_B).readForProject(secondRoot, WORKSPACE_ID)

    assertTrue(copied.ownership.isEmpty())
    assertEquals(VcsMappingDiagnosticCode.OWNERSHIP_CONFLICT, copied.diagnostics.single().code)
  }

  @Test
  fun `two services from the same generation cannot overwrite each other`() {
    val root = workspaceRoot()
    Files.createDirectory(root.resolve("repo-a"))
    Files.createDirectory(root.resolve("repo-b"))
    val firstService = service(EPOCH_A)
    val secondService = service(EPOCH_B)
    val firstLoad = firstService.readForProject(root, WORKSPACE_ID)
    val secondLoad = secondService.readForProject(root, WORKSPACE_ID)
    val firstCommit = firstService.recorderForProject(root, WORKSPACE_ID, firstLoad).prepare(
      VcsMappingOwnershipState(
        stableMappings = listOf(VcsMappingOwnership("repo-a", VcsMappingOwnershipKind.CREATED)),
      ),
    )
    val staleCommit = secondService.recorderForProject(root, WORKSPACE_ID, secondLoad).prepare(
      VcsMappingOwnershipState(
        stableMappings = listOf(VcsMappingOwnership("repo-b", VcsMappingOwnershipKind.CREATED)),
      ),
    )

    firstCommit.persistAndVerify()

    assertThrows(IllegalStateException::class.java) { staleCommit.persistAndVerify() }
    assertEquals(
      listOf("repo-a"),
      firstService.readForProject(root, WORKSPACE_ID).ownership.map { it.relativeDirectory },
    )
  }

  @Test
  fun `concurrent services from one generation allow at most one locked winner`() {
    val root = workspaceRoot()
    Files.createDirectory(root.resolve("repo-a"))
    Files.createDirectory(root.resolve("repo-b"))
    val startBarrier = CyclicBarrier(2)
    val writerLocked = CountDownLatch(1)
    val releaseWinner = CountDownLatch(1)
    val loserFinished = CountDownLatch(1)
    fun competingService(epoch: String) = ReqwsVcsOwnershipStateService(
      writerEpoch = epoch,
      beforeWriterLockAttempt = { startBarrier.await(5, TimeUnit.SECONDS) },
      afterWriterLockAcquired = {
        writerLocked.countDown()
        check(releaseWinner.await(5, TimeUnit.SECONDS)) { "Timed out holding writer lock" }
      },
    )
    val firstService = competingService(EPOCH_A)
    val secondService = competingService(EPOCH_B)
    val firstCommit = firstService.recorderForProject(
      root,
      WORKSPACE_ID,
      firstService.readForProject(root, WORKSPACE_ID),
    ).prepare(
      VcsMappingOwnershipState(
        stableMappings = listOf(VcsMappingOwnership("repo-a", VcsMappingOwnershipKind.CREATED)),
      ),
    )
    val secondCommit = secondService.recorderForProject(
      root,
      WORKSPACE_ID,
      secondService.readForProject(root, WORKSPACE_ID),
    ).prepare(
      VcsMappingOwnershipState(
        stableMappings = listOf(VcsMappingOwnership("repo-b", VcsMappingOwnershipKind.CREATED)),
      ),
    )
    val executor = Executors.newFixedThreadPool(2)
    fun submit(commit: VcsMappingOwnershipCommit) = executor.submit<Boolean> {
      try {
        commit.persistAndVerify()
        true
      } catch (_: Exception) {
        loserFinished.countDown()
        false
      }
    }

    try {
      val first = submit(firstCommit)
      val second = submit(secondCommit)
      assertTrue(writerLocked.await(5, TimeUnit.SECONDS))
      assertTrue(loserFinished.await(5, TimeUnit.SECONDS))
      releaseWinner.countDown()
      val firstWon = first.get(5, TimeUnit.SECONDS)
      val secondWon = second.get(5, TimeUnit.SECONDS)

      assertEquals(1, listOf(firstWon, secondWon).count { it })
      val expectedDirectory = if (firstWon) "repo-a" else "repo-b"
      assertEquals(
        listOf(expectedDirectory),
        service("33333333333333333333333333333333")
          .readForProject(root, WORKSPACE_ID)
          .ownership
          .map { it.relativeDirectory },
      )
    } finally {
      releaseWinner.countDown()
      executor.shutdownNow()
    }
  }

  @Test
  fun `external generation advance between transition and final rejects the old plan`() {
    val root = workspaceRoot()
    Files.createDirectory(root.resolve("repo-a"))
    val firstService = service(EPOCH_A)
    persist(
      firstService,
      root,
      VcsMappingOwnershipState(
        stableMappings = listOf(VcsMappingOwnership("repo-a", VcsMappingOwnershipKind.CREATED)),
      ),
    )
    val firstLoad = firstService.readForProject(root, WORKSPACE_ID)
    val firstRecorder = firstService.recorderForProject(root, WORKSPACE_ID, firstLoad)
    firstRecorder.prepare(
      VcsMappingOwnershipState(
        stableMappings = emptyList(),
        pendingAdds = listOf(VcsMappingPendingOwnership("repo-a", TOKEN_A)),
      ),
    ).persistAndVerify()

    val externalService = service(EPOCH_B)
    val externalLoad = externalService.readForProject(root, WORKSPACE_ID)
    externalService.recorderForProject(root, WORKSPACE_ID, externalLoad)
      .prepare(
        VcsMappingOwnershipState(
          stableMappings = listOf(
            VcsMappingOwnership("repo-a", VcsMappingOwnershipKind.BORROWED),
          ),
        ),
      )
      .persistAndVerify()

    val staleFinal = firstRecorder.prepare(
      VcsMappingOwnershipState(
        stableMappings = listOf(
          VcsMappingOwnership("repo-a", VcsMappingOwnershipKind.CREATED),
        ),
      ),
    )

    assertThrows(IllegalStateException::class.java) { staleFinal.persistAndVerify() }
    assertEquals(
      listOf(VcsMappingOwnership("repo-a", VcsMappingOwnershipKind.BORROWED)),
      externalService.readForProject(root, WORKSPACE_ID).ownership,
    )
  }

  @Test
  fun `overlapping operating system lock prevents an ownership replacement`() {
    val root = workspaceRoot()
    Files.createDirectory(root.resolve("repo-a"))
    val service = service(EPOCH_A)
    val loaded = service.readForProject(root, WORKSPACE_ID)
    val commit = service.recorderForProject(root, WORKSPACE_ID, loaded).prepare(
      VcsMappingOwnershipState(
        stableMappings = listOf(VcsMappingOwnership("repo-a", VcsMappingOwnershipKind.CREATED)),
      ),
    )
    val lockFile = root.resolve(".idea").resolve(ReqwsVcsOwnershipStateService.LOCK_FILE_NAME)
    Files.createFile(lockFile)

    FileChannel.open(lockFile, StandardOpenOption.WRITE).use { channel ->
      channel.lock().use {
        assertThrows(IllegalStateException::class.java) { commit.persistAndVerify() }
      }
    }

    assertFalse(Files.exists(stateFile(root)))
  }

  private fun workspaceRoot(): Path {
    val root = temporaryFolder.newFolder().toPath().toRealPath()
    Files.createDirectory(root.resolve(".idea"))
    return root
  }

  private fun service(epoch: String) = ReqwsVcsOwnershipStateService(epoch)

  private fun persist(
    service: ReqwsVcsOwnershipStateService,
    root: Path,
    state: VcsMappingOwnershipState,
    workspaceId: String = WORKSPACE_ID,
  ) {
    val loaded = service.readForProject(root, workspaceId)
    service.recorderForProject(root, workspaceId, loaded)
      .prepare(state)
      .persistAndVerify()
  }

  private fun stateFile(root: Path): Path =
    root.resolve(".idea").resolve(ReqwsVcsOwnershipStateService.STATE_FILE_NAME)

  companion object {
    private const val WORKSPACE_ID = "ws_test"
    private const val TOKEN_A = "0123456789abcdef0123456789abcdef"
    private const val TOKEN_B = "fedcba9876543210fedcba9876543210"
    private const val EPOCH_A = "11111111111111111111111111111111"
    private const val EPOCH_B = "22222222222222222222222222222222"
  }
}
