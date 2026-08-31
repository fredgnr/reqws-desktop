package com.reqws.goland.diagnostics

import com.reqws.goland.manifest.ManifestSnapshot
import com.reqws.goland.manifest.RepositoryAvailability
import com.reqws.goland.manifest.ResolvedRepository
import com.reqws.goland.manifest.WorkspaceManifest
import com.reqws.goland.manifest.WorkspaceRepository
import com.reqws.goland.project.ReqwsLifecycleState
import com.reqws.goland.project.ReqwsProjectState
import com.reqws.goland.vcs.VcsRepositoryInspection
import com.reqws.goland.vcs.VcsRepositoryStatus
import com.reqws.goland.vcs.VcsRootInspection
import com.reqws.goland.vcs.VcsWorkspaceDiagnosticCode
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
      state = ReqwsProjectState(
        lifecycle = ReqwsLifecycleState.DEGRADED,
        snapshot = snapshot,
        vcsInspection = VcsRootInspection(
          repositoryStatuses = listOf(
            VcsRepositoryInspection(0, VcsRepositoryStatus.NOT_CONFIGURED),
          ),
          workspaceDiagnostics = listOf(VcsWorkspaceDiagnosticCode.INACTIVE_GIT_ROOT),
        ),
      ),
      userHome = home,
    )

    assertTrue(output.contains("projectRoot=~/work/private-workspace"))
    assertTrue(output.contains("manifestPath=~/work/private-workspace/.reqws/workspace.json"))
    assertTrue(output.contains("repositoryCount=1"))
    assertTrue(output.contains("vcsMode=READ_ONLY_MANUAL"))
    assertTrue(output.contains("manualGitRootCount=1"))
    assertTrue(output.contains("vcsDiagnosticCode=VCS_CONFIGURATION_MISMATCH"))
    assertTrue(output.contains("vcsRepositoryStatuses=0:NOT_CONFIGURED"))
    assertTrue(output.contains("vcsWorkspaceDiagnostics=INACTIVE_GIT_ROOT"))
    assertFalse(output.contains("alice:token"))
    assertFalse(output.contains("example.test"))
    assertFalse(output.contains("secret-workspace-id"))
    assertFalse(output.contains("Secret Workspace"))
    assertFalse(output.contains("rootSettings"))
  }

  @Test
  fun `redacts absolute paths outside the user home`() {
    assertTrue(
      ReqwsDiagnostics.redactPath(Path.of("/private/var/workspace"), Path.of("/Users/alice")) ==
        "<absolute>/workspace",
    )
  }
}
