package com.reqws.goland.vcs

import com.reqws.goland.manifest.ManifestSnapshot
import com.reqws.goland.manifest.RepositoryAvailability
import com.reqws.goland.manifest.ResolvedRepository
import com.reqws.goland.manifest.WorkspaceManifest
import com.reqws.goland.manifest.WorkspaceRepository
import java.nio.file.Files
import java.nio.file.Path
import java.text.Normalizer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class VcsRootInspectorTest {
  @Rule
  @JvmField
  val temporaryFolder = TemporaryFolder()

  private val inspector = VcsRootInspector()

  @Test
  fun `classifies exact missing wrong duplicate and unconfigured repositories`() {
    val root = workspaceRoot()
    gitRepository(root, "configured")
    Files.createDirectories(root.resolve("not-git"))
    gitRepository(root, "unconfigured")
    gitRepository(root, "wrong")
    gitRepository(root, "duplicate")
    val snapshot = snapshot(
      root,
      listOf("configured", "missing", "not-git", "unconfigured", "wrong", "duplicate"),
    )

    val result = inspector.inspect(
      snapshot = snapshot,
      gitAvailable = true,
      mappings = listOf(
        mapping(root.resolve("configured"), rootSettings = true),
        mapping(root.resolve("wrong"), vcs = "Mercurial"),
        mapping(root.resolve("duplicate")),
        mapping(root.resolve("duplicate").toString() + "/."),
      ),
    )

    assertEquals(
      listOf(
        VcsRepositoryStatus.CONFIGURED,
        VcsRepositoryStatus.MISSING_DIRECTORY,
        VcsRepositoryStatus.NOT_GIT,
        VcsRepositoryStatus.NOT_CONFIGURED,
        VcsRepositoryStatus.WRONG_VCS,
        VcsRepositoryStatus.DUPLICATE,
      ),
      result.repositoryStatuses.map { it.status },
    )
    assertEquals((0..5).toList(), result.repositoryStatuses.map { it.repositoryIndex })
    assertTrue(result.degraded)
    assertTrue(result.requiresManualConfiguration)
    assertEquals("REPOSITORY_NOT_GIT", result.stableErrorCode())
  }

  @Test
  fun `reports workspace-wide and inactive Git roots but ignores mappings outside the workspace`() {
    val root = workspaceRoot()
    gitRepository(root, "active")
    gitRepository(root, "retained")
    val outside = temporaryFolder.newFolder("outside").toPath()
    val snapshot = snapshot(root, listOf("active"))

    val result = inspector.inspect(
      snapshot = snapshot,
      gitAvailable = true,
      mappings = listOf(
        mapping(root.resolve("active")),
        ObservedVcsMapping("", GIT_VCS_NAME, hasRootSettings = false),
        mapping(root.resolve("retained")),
        mapping(outside),
      ),
    )

    assertEquals(VcsRepositoryStatus.CONFIGURED, result.repositoryStatuses.single().status)
    assertEquals(
      listOf(
        VcsWorkspaceDiagnosticCode.WORKSPACE_WIDE_GIT_ROOT,
        VcsWorkspaceDiagnosticCode.INACTIVE_GIT_ROOT,
      ),
      result.workspaceDiagnostics,
    )
    assertTrue(result.requiresManualConfiguration)
    assertEquals("VCS_CONFIGURATION_MISMATCH", result.stableErrorCode())
  }

  @Test
  fun `ignores nested nonexistent and ordinary extra mappings inside the workspace`() {
    val root = workspaceRoot()
    gitRepository(root, "active")
    Files.createDirectories(root.resolve("ordinary"))
    gitRepository(root.resolve("nested-parent"), "nested")

    val result = inspector.inspect(
      snapshot = snapshot(root, listOf("active")),
      gitAvailable = true,
      mappings = listOf(
        mapping(root.resolve("active")),
        mapping(root.resolve("ordinary")),
        mapping(root.resolve("missing-extra")),
        mapping(root.resolve("nested-parent/nested")),
      ),
    )

    assertEquals(VcsRepositoryStatus.CONFIGURED, result.repositoryStatuses.single().status)
    assertEquals(emptyList<VcsWorkspaceDiagnosticCode>(), result.workspaceDiagnostics)
    assertFalse(result.degraded)
  }

  @Test
  fun `keeps a snapshot missing repository degraded until a new candidate is read`() {
    val root = workspaceRoot()
    val missingSnapshot = snapshot(root, listOf("appeared-later"))
    gitRepository(root, "appeared-later")

    val result = inspector.inspect(
      snapshot = missingSnapshot,
      gitAvailable = true,
      mappings = listOf(mapping(root.resolve("appeared-later"))),
    )

    assertEquals(VcsRepositoryStatus.MISSING_DIRECTORY, result.repositoryStatuses.single().status)
    assertTrue(result.degraded)
  }

  @Test
  fun `matches Unicode normalization and canonical symlink identities`() {
    val root = workspaceRoot()
    val composedName = "caf\u00e9"
    val decomposedName = Normalizer.normalize(composedName, Normalizer.Form.NFD)
    gitRepository(root, composedName)
    gitRepository(root, "target")
    val alias = root.resolve("target-alias")
    Files.createSymbolicLink(alias, root.resolve("target"))

    val unicodeResult = inspector.inspect(
      snapshot(root, listOf(composedName)),
      gitAvailable = true,
      mappings = listOf(mapping(root.resolve(decomposedName))),
    )
    val aliasResult = inspector.inspect(
      snapshot(root, listOf("target")),
      gitAvailable = true,
      mappings = listOf(mapping(alias)),
    )

    assertEquals(VcsRepositoryStatus.CONFIGURED, unicodeResult.repositoryStatuses.single().status)
    assertEquals(VcsRepositoryStatus.CONFIGURED, aliasResult.repositoryStatuses.single().status)
    assertFalse(unicodeResult.degraded)
    assertFalse(aliasResult.degraded)
    assertNull(unicodeResult.stableErrorCode())
  }

  @Test
  fun `reports unavailable Git integration without claiming configured roots`() {
    val root = workspaceRoot()
    gitRepository(root, "repository")

    val result = inspector.inspect(
      snapshot(root, listOf("repository")),
      gitAvailable = false,
      mappings = listOf(mapping(root.resolve("repository"))),
    )

    assertEquals(VcsRepositoryStatus.NOT_CONFIGURED, result.repositoryStatuses.single().status)
    assertEquals(
      listOf(VcsWorkspaceDiagnosticCode.GIT_PLUGIN_UNAVAILABLE),
      result.workspaceDiagnostics,
    )
    assertTrue(result.degraded)
    assertTrue(result.requiresManualConfiguration)
    assertEquals("GIT_PLUGIN_UNAVAILABLE", result.stableErrorCode())
  }

  @Test
  fun `classifies fifty repositories while retaining twenty inactive mappings`() {
    val root = workspaceRoot()
    val active = (0 until 50).map { index -> "active-$index" }
    val retained = (0 until 20).map { index -> "retained-$index" }
    (active + retained).forEach { name -> gitRepository(root, name) }
    val mappings = (active + retained).map { name -> mapping(root.resolve(name)) }

    val result = inspector.inspect(
      snapshot(root, active),
      gitAvailable = true,
      mappings = mappings,
    )

    assertEquals(50, result.repositoryStatuses.size)
    assertTrue(result.repositoryStatuses.all { it.status == VcsRepositoryStatus.CONFIGURED })
    assertEquals(
      listOf(VcsWorkspaceDiagnosticCode.INACTIVE_GIT_ROOT),
      result.workspaceDiagnostics,
    )
  }

  private fun workspaceRoot(): Path = temporaryFolder.newFolder("workspace").toPath().toRealPath()

  private fun gitRepository(root: Path, name: String) {
    Files.createDirectories(root.resolve(name).resolve(".git"))
  }

  private fun mapping(
    path: Path,
    vcs: String = GIT_VCS_NAME,
    rootSettings: Boolean = false,
  ): ObservedVcsMapping = mapping(path.toString(), vcs, rootSettings)

  private fun mapping(
    directory: String,
    vcs: String = GIT_VCS_NAME,
    rootSettings: Boolean = false,
  ): ObservedVcsMapping = ObservedVcsMapping(directory, vcs, rootSettings)

  private fun snapshot(root: Path, repositoryNames: List<String>): ManifestSnapshot {
    val repositories = repositoryNames.mapIndexed { index, name ->
      val repository = WorkspaceRepository(
        catalogRepositoryId = "repo_$index",
        name = name,
        url = "https://example.invalid/repository.git",
        defaultBranch = "main",
        relativePath = name,
      )
      val path = root.resolve(name)
      ResolvedRepository(
        repository = repository,
        path = path,
        canonicalPath = if (Files.exists(path)) path.toRealPath() else null,
        availability = if (Files.exists(path)) {
          RepositoryAvailability.PRESENT
        } else {
          RepositoryAvailability.MISSING
        },
      )
    }
    return ManifestSnapshot(
      manifest = WorkspaceManifest(
        schemaVersion = 1,
        id = "ws_test",
        name = "test",
        featureBranch = "feature/test",
        rootPath = root.toString(),
        workspaceFilePath = root.resolveSibling("test.code-workspace").toString(),
        repositories = repositories.map { it.repository },
        createdAt = "2026-08-14T00:00:00.000Z",
        updatedAt = "2026-08-14T00:00:00.000Z",
      ),
      manifestPath = root.resolve(".reqws/workspace.json"),
      canonicalProjectRoot = root,
      repositories = repositories,
      digestSha256 = "a".repeat(64),
      diagnostics = emptyList(),
    )
  }
}
