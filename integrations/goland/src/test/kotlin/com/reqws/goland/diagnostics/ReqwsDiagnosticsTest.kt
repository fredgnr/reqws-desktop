package com.reqws.goland.diagnostics

import com.reqws.goland.manifest.ManifestSnapshot
import com.reqws.goland.manifest.RepositoryAvailability
import com.reqws.goland.manifest.ResolvedRepository
import com.reqws.goland.manifest.WorkspaceManifest
import com.reqws.goland.manifest.WorkspaceRepository
import com.reqws.goland.project.ReqwsLifecycleState
import com.reqws.goland.project.ReqwsProjectState
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Path

class ReqwsDiagnosticsTest {
  @Test
  fun `redacts home paths and never serializes manifest credentials`() {
    val home = Path.of("/Users/alice")
    val root = home.resolve("work/private-workspace")
    val repository = WorkspaceRepository(
      catalogRepositoryId = "secret-id",
      name = "private-repository",
      url = "https://alice:token@example.test/private.git",
      defaultBranch = "main",
      relativePath = "private-repository",
    )
    val snapshot = ManifestSnapshot(
      manifest = WorkspaceManifest(
        schemaVersion = 1,
        id = "secret-workspace-id",
        name = "Secret Workspace",
        featureBranch = "feature/secret",
        rootPath = root.toString(),
        workspaceFilePath = root.resolve("secret.code-workspace").toString(),
        repositories = listOf(repository),
        createdAt = "2026-08-14T00:00:00.000Z",
        updatedAt = "2026-08-14T00:00:00.000Z",
      ),
      manifestPath = root.resolve(".reqws/workspace.json"),
      canonicalProjectRoot = root,
      repositories = listOf(
        ResolvedRepository(
          repository = repository,
          path = root.resolve(repository.relativePath),
          canonicalPath = root.resolve(repository.relativePath),
          availability = RepositoryAvailability.PRESENT,
        ),
      ),
      digestSha256 = "a".repeat(64),
      diagnostics = emptyList(),
    )

    val output = ReqwsDiagnostics.format(
      pluginVersion = "0.1.0",
      ideBuild = "GO-261.1",
      projectRoot = root,
      state = ReqwsProjectState(ReqwsLifecycleState.SYNCHRONIZED, snapshot),
      userHome = home,
    )

    assertTrue(output.contains("projectRoot=~/work/private-workspace"))
    assertTrue(output.contains("manifestPath=~/work/private-workspace/.reqws/workspace.json"))
    assertTrue(output.contains("repositoryCount=1"))
    assertFalse(output.contains("alice:token"))
    assertFalse(output.contains("example.test"))
    assertFalse(output.contains("secret-workspace-id"))
    assertFalse(output.contains("Secret Workspace"))
  }

  @Test
  fun `redacts absolute paths outside the user home`() {
    assertTrue(
      ReqwsDiagnostics.redactPath(Path.of("/private/var/workspace"), Path.of("/Users/alice")) ==
        "<absolute>/workspace",
    )
  }
}
