package com.reqws.goland.ui

import com.reqws.goland.ReqwsBundle
import com.reqws.goland.manifest.ManifestDiagnostic
import com.reqws.goland.manifest.ManifestErrorCode
import com.reqws.goland.manifest.ManifestSnapshot
import com.reqws.goland.manifest.RepositoryAvailability
import com.reqws.goland.manifest.ResolvedRepository
import com.reqws.goland.manifest.WorkspaceManifest
import com.reqws.goland.manifest.WorkspaceRepository
import com.reqws.goland.project.ReqwsLifecycleState
import com.reqws.goland.project.ReqwsProjectError
import com.reqws.goland.project.ReqwsProjectState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Path

class ReqwsToolWindowViewModelTest {
  @Test
  fun `maps synchronized snapshots to localized resource keys`() {
    val model = ReqwsToolWindowViewModel.from(
      ReqwsProjectState(
        lifecycle = ReqwsLifecycleState.SYNCHRONIZED,
        snapshot = snapshot(),
        lastAppliedDigest = snapshot().digestSha256,
      ),
    )

    assertTrue(model.visible)
    assertEquals("Workspace", model.workspaceName)
    assertEquals("feature/read-only", model.featureBranch)
    assertEquals("state.synchronized", model.statusKey)
    assertEquals(null, model.statusDetailKey)
    assertEquals(ReqwsStatusTone.SUCCESS, model.statusTone)
    assertEquals(listOf("repository.active", "repository.missing"), model.repositories.map { it.statusKey })
    assertEquals(
      listOf(ReqwsStatusTone.SUCCESS, ReqwsStatusTone.WARNING),
      model.repositories.map { it.statusTone },
    )
    assertEquals("0123456789ab", model.digest)
    assertTrue(model.syncEnabled)
  }

  @Test
  fun `keeps the previous snapshot visible for an invalid manifest`() {
    val model = ReqwsToolWindowViewModel.from(
      ReqwsProjectState(
        lifecycle = ReqwsLifecycleState.ERROR,
        snapshot = snapshot(),
        lastAppliedDigest = snapshot().digestSha256,
        lastError = ReqwsProjectError(ManifestErrorCode.MANIFEST_INVALID_JSON.name),
      ),
    )

    assertEquals("state.error", model.statusKey)
    assertEquals(ReqwsStatusTone.ERROR, model.statusTone)
    assertEquals("MANIFEST_INVALID_JSON", model.errorCode)
    assertTrue(model.preservedSnapshot)
    assertTrue(model.copyDiagnosticsEnabled)
    val details = formatDetailsText(model)
    assertTrue(details.orEmpty().contains("MANIFEST_INVALID_JSON"))
    assertTrue(details.orEmpty().contains(ReqwsBundle.message("message.preservedModel")))
  }

  @Test
  fun `preserves long html shaped manifest values as unmodified display data`() {
    val workspaceName = "<html><img src='https://example.test/tracker'>workspace-" + "x".repeat(512)
    val featureBranch = "feature/<b>literal-branch</b>"
    val repositoryName = "<html>repository-" + "y".repeat(512)
    val original = snapshot()
    val untrustedSnapshot = original.copy(
      manifest = original.manifest.copy(
        name = workspaceName,
        featureBranch = featureBranch,
      ),
      repositories = original.repositories.mapIndexed { index, repository ->
        if (index == 0) {
          repository.copy(repository = repository.repository.copy(name = repositoryName))
        } else {
          repository
        }
      },
    )

    val model = ReqwsToolWindowViewModel.from(
      ReqwsProjectState(
        lifecycle = ReqwsLifecycleState.SYNCHRONIZED,
        snapshot = untrustedSnapshot,
      ),
    )

    assertEquals(workspaceName, model.workspaceName)
    assertEquals(featureBranch, model.featureBranch)
    assertEquals(repositoryName, model.repositories.first().name)
  }

  @Test
  fun `hides an initial read before a ReqWS manifest is found`() {
    val model = ReqwsToolWindowViewModel.from(
      ReqwsProjectState(lifecycle = ReqwsLifecycleState.READING),
    )

    assertFalse(model.visible)
  }

  @Test
  fun `keeps a previous snapshot visible while rereading the manifest`() {
    val model = ReqwsToolWindowViewModel.from(
      ReqwsProjectState(
        lifecycle = ReqwsLifecycleState.READING,
        snapshot = snapshot(),
      ),
    )

    assertTrue(model.visible)
  }

  @Test
  fun `disables every action after disposal`() {
    val model = ReqwsToolWindowViewModel.from(ReqwsProjectState.DISPOSED)

    assertFalse(model.visible)
    assertFalse(model.syncEnabled)
    assertFalse(model.openManifestEnabled)
    assertFalse(model.copyDiagnosticsEnabled)
  }

  @Test
  fun `maps progress warning and terminal lifecycles to accessible status tones`() {
    val tones = ReqwsLifecycleState.entries.associateWith { lifecycle ->
      ReqwsToolWindowViewModel.from(ReqwsProjectState(lifecycle)).statusTone
    }

    assertEquals(ReqwsStatusTone.NEUTRAL, tones[ReqwsLifecycleState.INACTIVE])
    assertEquals(ReqwsStatusTone.INFO, tones[ReqwsLifecycleState.READING])
    assertEquals(ReqwsStatusTone.WARNING, tones[ReqwsLifecycleState.SAFE_MODE_BLOCKED])
    assertEquals(ReqwsStatusTone.INFO, tones[ReqwsLifecycleState.SYNCHRONIZING])
    assertEquals(ReqwsStatusTone.SUCCESS, tones[ReqwsLifecycleState.SYNCHRONIZED])
    assertEquals(ReqwsStatusTone.WARNING, tones[ReqwsLifecycleState.DEGRADED])
    assertEquals(ReqwsStatusTone.ERROR, tones[ReqwsLifecycleState.ERROR])
    assertEquals(ReqwsStatusTone.NEUTRAL, tones[ReqwsLifecycleState.DISPOSED])
  }

  @Test
  fun `keeps the Safe Mode status compact and exposes a separate recovery hint`() {
    val model = ReqwsToolWindowViewModel.from(
      ReqwsProjectState(
        lifecycle = ReqwsLifecycleState.SAFE_MODE_BLOCKED,
        snapshot = snapshot(),
      ),
    )

    assertEquals("state.safeModeBlocked", model.statusKey)
    assertEquals("message.safeModeHint", model.statusDetailKey)
    assertEquals(ReqwsStatusTone.WARNING, model.statusTone)
  }

  private fun snapshot(): ManifestSnapshot {
    val root = Path.of("/tmp/workspace")
    val repositories = listOf(
      repository("api", root.resolve("api"), RepositoryAvailability.PRESENT),
      repository("worker", root.resolve("worker"), RepositoryAvailability.MISSING),
    )
    return ManifestSnapshot(
      manifest = WorkspaceManifest(
        schemaVersion = 1,
        id = "workspace",
        name = "Workspace",
        featureBranch = "feature/read-only",
        rootPath = root.toString(),
        workspaceFilePath = root.resolve("workspace.code-workspace").toString(),
        repositories = repositories.map { it.repository },
        createdAt = "2026-08-14T00:00:00.000Z",
        updatedAt = "2026-08-14T00:00:00.000Z",
      ),
      manifestPath = root.resolve(".reqws/workspace.json"),
      canonicalProjectRoot = root,
      repositories = repositories,
      digestSha256 = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
      diagnostics = listOf(
        ManifestDiagnostic(
          code = ManifestErrorCode.REPOSITORY_MISSING,
          severity = com.reqws.goland.manifest.ManifestDiagnosticSeverity.WARNING,
          repositoryIndex = 1,
        ),
      ),
    )
  }

  private fun repository(
    name: String,
    path: Path,
    availability: RepositoryAvailability,
  ) = ResolvedRepository(
    repository = WorkspaceRepository(
      catalogRepositoryId = "repo_$name",
      name = name,
      url = "https://secret@example.test/$name.git",
      defaultBranch = "main",
      relativePath = name,
    ),
    path = path,
    canonicalPath = path.takeIf { availability == RepositoryAvailability.PRESENT },
    availability = availability,
  )
}
