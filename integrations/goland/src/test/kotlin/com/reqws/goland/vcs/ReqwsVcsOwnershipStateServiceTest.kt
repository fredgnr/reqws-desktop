package com.reqws.goland.vcs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Files
import java.nio.file.Path

class ReqwsVcsOwnershipStateServiceTest {
  @Rule
  @JvmField
  val temporaryFolder = TemporaryFolder()

  @Test
  fun `persists only the version relative directory and ownership kind`() {
    val root = workspaceRoot()
    Files.createDirectory(root.resolve("repo-a"))
    val service = ReqwsVcsOwnershipStateService()

    service.replaceForProject(
      root,
      listOf(VcsMappingOwnership("repo-a", VcsMappingOwnershipKind.CREATED)),
    )

    val state = service.state
    assertEquals(ReqwsVcsOwnershipStateService.CURRENT_STATE_VERSION, state.stateVersion)
    assertEquals("repo-a", state.managedMappings.single().relativeDirectory)
    assertEquals("CREATED", state.managedMappings.single().kind)
    assertFalse(state.toString().contains(root.toString()))
    assertFalse(state.toString().contains("http"))
  }

  @Test
  fun `unsupported state version authorizes no mappings`() {
    val root = workspaceRoot()
    val service = ReqwsVcsOwnershipStateService()
    service.loadState(
      ReqwsVcsOwnershipStateService.PersistedState(
        stateVersion = 2,
        managedMappings = mutableListOf(
          ReqwsVcsOwnershipStateService.PersistedMapping("repo-a", "CREATED"),
        ),
      ),
    )

    val loaded = service.readForProject(root)

    assertTrue(loaded.ownership.isEmpty())
    assertEquals(VcsMappingDiagnosticCode.OWNERSHIP_CONFLICT, loaded.diagnostics.single().code)
  }

  @Test
  fun `malformed absolute dotted and symlink paths authorize no removals`() {
    val root = workspaceRoot()
    val outside = temporaryFolder.newFolder("outside").toPath().toRealPath()
    Files.createSymbolicLink(root.resolve("escape"), outside)
    val service = ReqwsVcsOwnershipStateService()
    service.loadState(
      ReqwsVcsOwnershipStateService.PersistedState(
        managedMappings = mutableListOf(
          ReqwsVcsOwnershipStateService.PersistedMapping(outside.toString(), "CREATED"),
          ReqwsVcsOwnershipStateService.PersistedMapping("repo/.", "CREATED"),
          ReqwsVcsOwnershipStateService.PersistedMapping("escape", "CREATED"),
          ReqwsVcsOwnershipStateService.PersistedMapping("repo-a", "UNKNOWN"),
        ),
      ),
    )

    val loaded = service.readForProject(root)

    assertTrue(loaded.ownership.isEmpty())
    assertEquals(4, loaded.diagnostics.size)
    assertTrue(loaded.diagnostics.all { it.code == VcsMappingDiagnosticCode.OWNERSHIP_CONFLICT })
  }

  @Test
  fun `returned state is isolated from stored ownership`() {
    val root = workspaceRoot()
    val service = ReqwsVcsOwnershipStateService()
    service.replaceForProject(
      root,
      listOf(VcsMappingOwnership("repo-a", VcsMappingOwnershipKind.BORROWED)),
    )

    service.state.managedMappings.clear()

    assertEquals(1, service.readForProject(root).ownership.size)
  }

  private fun workspaceRoot(): Path = temporaryFolder.newFolder().toPath().toRealPath()
}
