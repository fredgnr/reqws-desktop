package com.reqws.goland.vcs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path

class ReqwsVcsOwnershipStateServiceTest {
  @Rule
  @JvmField
  val temporaryFolder = TemporaryFolder()

  @Test
  fun `atomically persists stable and pending v2 state and cold reads it back`() {
    val root = workspaceRoot()
    Files.createDirectory(root.resolve("repo-a"))
    Files.createDirectory(root.resolve("repo-b"))
    Files.createDirectory(root.resolve("repo-c"))
    val service = ReqwsVcsOwnershipStateService()
    val expected = VcsMappingOwnershipState(
      stableMappings = listOf(VcsMappingOwnership("repo-a", VcsMappingOwnershipKind.CREATED)),
      pendingAdds = listOf(VcsMappingPendingOwnership("repo-b", TOKEN_A)),
      pendingRemovals = listOf(VcsMappingPendingOwnership("repo-c", TOKEN_B)),
    )

    service.persistPreparedReplacement(service.prepareReplacementForProject(root, expected))
    val cold = ReqwsVcsOwnershipStateService().readForProject(root)

    assertEquals(expected.stableMappings, cold.ownership)
    assertEquals(expected.pendingAdds, cold.pendingAdds)
    assertEquals(expected.pendingRemovals, cold.pendingRemovals)
    assertTrue(cold.diagnostics.isEmpty())
    val json = Files.readString(stateFile(root), StandardCharsets.UTF_8)
    assertTrue(json.contains("\"stateVersion\":2"))
    assertTrue(json.contains("\"pendingRemovals\""))
    assertFalse(json.contains(root.toString()))
    assertFalse(json.contains("http"))
  }

  @Test
  fun `cold pending tombstones never appear in deletion-authorizing ownership`() {
    val root = workspaceRoot()
    Files.createDirectory(root.resolve("repo-a"))
    Files.createDirectory(root.resolve("repo-b"))
    val service = ReqwsVcsOwnershipStateService()
    service.persistPreparedReplacement(
      service.prepareReplacementForProject(
        root,
        VcsMappingOwnershipState(
          stableMappings = emptyList(),
          pendingAdds = listOf(VcsMappingPendingOwnership("repo-a", TOKEN_A)),
          pendingRemovals = listOf(VcsMappingPendingOwnership("repo-b", TOKEN_B)),
        ),
      ),
    )

    val loaded = ReqwsVcsOwnershipStateService().readForProject(root)

    assertTrue(loaded.ownership.isEmpty())
    assertEquals(listOf("repo-a"), loaded.pendingAdds.map { it.relativeDirectory })
    assertEquals(listOf("repo-b"), loaded.pendingRemovals.map { it.relativeDirectory })
  }

  @Test
  fun `malformed unsupported and cross-phase duplicate state authorizes no mapping`() {
    val root = workspaceRoot()
    Files.createDirectory(root.resolve("repo-a"))
    Files.writeString(
      stateFile(root),
      """{"stateVersion":2,"stableMappings":[{"relativeDirectory":"repo-a","kind":"CREATED"}],"pendingAdds":[],"pendingRemovals":[{"relativeDirectory":"repo-a","operationToken":"$TOKEN_A"}]}""",
      StandardCharsets.UTF_8,
    )

    val duplicate = ReqwsVcsOwnershipStateService().readForProject(root)
    assertTrue(duplicate.ownership.isEmpty())
    assertTrue(duplicate.pendingRemovals.isEmpty())
    assertEquals(VcsMappingDiagnosticCode.OWNERSHIP_CONFLICT, duplicate.diagnostics.single().code)

    Files.writeString(
      stateFile(root),
      """{"stateVersion":1,"stableMappings":[],"pendingAdds":[],"pendingRemovals":[]}""",
      StandardCharsets.UTF_8,
    )
    val unsupported = ReqwsVcsOwnershipStateService().readForProject(root)
    assertTrue(unsupported.ownership.isEmpty())
    assertEquals(VcsMappingDiagnosticCode.OWNERSHIP_CONFLICT, unsupported.diagnostics.single().code)

    Files.write(stateFile(root), byteArrayOf(0xc3.toByte(), 0x28))
    val invalidUtf8 = ReqwsVcsOwnershipStateService().readForProject(root)
    assertTrue(invalidUtf8.ownership.isEmpty())
    assertEquals(VcsMappingDiagnosticCode.OWNERSHIP_CONFLICT, invalidUtf8.diagnostics.single().code)
  }

  @Test
  fun `absolute dotted symlink and invalid token states fail closed`() {
    val root = workspaceRoot()
    val outside = temporaryFolder.newFolder("outside").toPath().toRealPath()
    Files.createSymbolicLink(root.resolve("escape"), outside)
    val service = ReqwsVcsOwnershipStateService()

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
      try {
        service.prepareReplacementForProject(root, state)
        throw AssertionError("Expected invalid ownership state")
      } catch (_: IllegalArgumentException) {
        // Expected.
      }
    }
  }

  @Test
  fun `unicode and escaped relative directories survive strict file readback`() {
    val root = workspaceRoot()
    val relative = "仓库-\\-\"quoted\""
    Files.createDirectory(root.resolve(relative))
    val service = ReqwsVcsOwnershipStateService()
    service.persistPreparedReplacement(
      service.prepareReplacementForProject(
        root,
        VcsMappingOwnershipState(
          stableMappings = listOf(VcsMappingOwnership(relative, VcsMappingOwnershipKind.BORROWED)),
        ),
      ),
    )

    assertEquals(relative, ReqwsVcsOwnershipStateService().readForProject(root).ownership.single().relativeDirectory)
  }

  @Test
  fun `legacy workspace component is migrated once into the verified atomic file`() {
    val root = workspaceRoot()
    Files.createDirectory(root.resolve("repo-a"))
    val service = ReqwsVcsOwnershipStateService()
    service.loadState(
      ReqwsVcsOwnershipStateService.LegacyPersistedState(
        managedMappings = mutableListOf(
          ReqwsVcsOwnershipStateService.LegacyPersistedMapping("repo-a", "CREATED"),
        ),
      ),
    )

    val migrated = service.readForProject(root)

    assertEquals(
      listOf(VcsMappingOwnership("repo-a", VcsMappingOwnershipKind.CREATED)),
      migrated.ownership,
    )
    assertTrue(Files.isRegularFile(stateFile(root)))
    assertTrue(service.state.managedMappings.isEmpty())
    assertEquals(migrated.ownership, ReqwsVcsOwnershipStateService().readForProject(root).ownership)
  }

  @Test
  fun `copied atomic ownership file cannot authorize mappings in another workspace root`() {
    val firstRoot = workspaceRoot()
    val secondRoot = workspaceRoot()
    Files.createDirectory(firstRoot.resolve("repo-a"))
    Files.createDirectory(secondRoot.resolve("repo-a"))
    val service = ReqwsVcsOwnershipStateService()
    service.persistPreparedReplacement(
      service.prepareReplacementForProject(
        firstRoot,
        VcsMappingOwnershipState(
          stableMappings = listOf(VcsMappingOwnership("repo-a", VcsMappingOwnershipKind.CREATED)),
        ),
      ),
    )
    Files.copy(stateFile(firstRoot), stateFile(secondRoot))

    val copied = ReqwsVcsOwnershipStateService().readForProject(secondRoot)

    assertTrue(copied.ownership.isEmpty())
    assertEquals(VcsMappingDiagnosticCode.OWNERSHIP_CONFLICT, copied.diagnostics.single().code)
  }

  @Test
  fun `stale prepared generation cannot overwrite a newer ownership checkpoint`() {
    val root = workspaceRoot()
    Files.createDirectory(root.resolve("repo-a"))
    Files.createDirectory(root.resolve("repo-b"))
    val service = ReqwsVcsOwnershipStateService()
    val first = service.prepareReplacementForProject(
      root,
      VcsMappingOwnershipState(
        stableMappings = listOf(VcsMappingOwnership("repo-a", VcsMappingOwnershipKind.CREATED)),
      ),
    )
    val stale = service.prepareReplacementForProject(
      root,
      VcsMappingOwnershipState(
        stableMappings = listOf(VcsMappingOwnership("repo-b", VcsMappingOwnershipKind.CREATED)),
      ),
    )
    service.persistPreparedReplacement(first)

    try {
      service.persistPreparedReplacement(stale)
      throw AssertionError("Expected stale ownership generation rejection")
    } catch (_: IllegalArgumentException) {
      // Expected.
    }
    assertEquals(listOf("repo-a"), service.readForProject(root).ownership.map { it.relativeDirectory })
  }

  private fun workspaceRoot(): Path {
    val root = temporaryFolder.newFolder().toPath().toRealPath()
    Files.createDirectory(root.resolve(".idea"))
    return root
  }

  private fun stateFile(root: Path): Path =
    root.resolve(".idea").resolve(ReqwsVcsOwnershipStateService.STATE_FILE_NAME)

  companion object {
    private const val TOKEN_A = "0123456789abcdef0123456789abcdef"
    private const val TOKEN_B = "fedcba9876543210fedcba9876543210"
  }
}
