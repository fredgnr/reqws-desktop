package com.reqws.goland.project

import com.reqws.goland.manifest.ManifestErrorCode
import com.reqws.goland.manifest.ManifestReader
import com.reqws.goland.vcs.VcsRepositoryInspection
import com.reqws.goland.vcs.VcsRepositoryStatus
import com.reqws.goland.vcs.VcsRootInspection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Files
import java.nio.file.Path

class ReqwsProjectLoadEngineTest {
  @Rule
  @JvmField
  val temporaryFolder = TemporaryFolder()

  @Test
  fun `keeps a read-only snapshot while safe mode blocks synchronization`() {
    val root = createWorkspace("safe-mode", listOf("api"))
    val engine = ReqwsProjectLoadEngine(ManifestReader(), ReqwsTrustGate { false })

    val state = engine.load(root, ReqwsProjectState.INACTIVE)

    assertEquals(ReqwsLifecycleState.SAFE_MODE_BLOCKED, state.lifecycle)
    assertEquals("Feature Login", state.snapshot?.manifest?.name)
    assertNull(state.lastError)
  }

  @Test
  fun `marks missing repositories degraded without creating them`() {
    val root = createWorkspace("missing", listOf("missing-repository"), createRepositories = false)
    val engine = ReqwsProjectLoadEngine(ManifestReader(), ReqwsTrustGate { true })

    val state = engine.load(root, ReqwsProjectState.INACTIVE)

    assertEquals(ReqwsLifecycleState.DEGRADED, state.lifecycle)
    assertEquals(1, state.snapshot?.missingRepositoryCount)
    assertEquals(false, Files.exists(root.resolve("missing-repository")))
  }

  @Test
  fun `preserves the previous valid snapshot after a redacted validation error`() {
    val root = createWorkspace("recovery", listOf("api"))
    val engine = ReqwsProjectLoadEngine(ManifestReader(), ReqwsTrustGate { true })
    val valid = engine.load(root, ReqwsProjectState.INACTIVE)
    val manifest = ReqwsProjectDetector.manifestPath(root)
    Files.writeString(manifest, "{")

    val failed = engine.load(root, valid)

    assertEquals(ReqwsLifecycleState.ERROR, failed.lifecycle)
    assertEquals(ManifestErrorCode.MANIFEST_INVALID_JSON.name, failed.lastError?.code)
    assertNotNull(failed.lastError?.digestSha256)
    assertEquals(valid.snapshot, failed.snapshot)
  }

  @Test
  fun `treats deletion after activation as a recoverable read error`() {
    val root = createWorkspace("deleted", listOf("api"))
    val engine = ReqwsProjectLoadEngine(ManifestReader(), ReqwsTrustGate { true })
    val valid = engine.load(root, ReqwsProjectState.INACTIVE).copy(
      lastAppliedDigest = "b".repeat(64),
    )
    Files.delete(ReqwsProjectDetector.manifestPath(root))

    val failed = engine.load(root, valid)

    assertEquals(ReqwsLifecycleState.ERROR, failed.lifecycle)
    assertEquals(ManifestErrorCode.MANIFEST_NOT_FOUND.name, failed.lastError?.code)
    assertEquals(valid.snapshot, failed.snapshot)
    assertEquals(valid.lastAppliedDigest, failed.lastAppliedDigest)
  }

  @Test
  fun `records a clean model digest while preserving read-only VCS degradation`() {
    val root = createWorkspace("manual-vcs", listOf("api"))
    val engine = ReqwsProjectLoadEngine(ManifestReader(), ReqwsTrustGate { true })
    val loaded = engine.load(root, ReqwsProjectState.INACTIVE).copy(
      lastError = ReqwsProjectError("stale-error"),
      vcsInspection = VcsRootInspection(
        repositoryStatuses = listOf(
          VcsRepositoryInspection(0, VcsRepositoryStatus.NOT_CONFIGURED),
        ),
        workspaceDiagnostics = emptyList(),
      ),
    )

    val applied = loaded.afterSuccessfulProjection("d".repeat(64))

    assertEquals(ReqwsLifecycleState.DEGRADED, applied.lifecycle)
    assertEquals("d".repeat(64), applied.lastAppliedDigest)
    assertNull(applied.lastError)
    assertEquals(VcsRepositoryStatus.NOT_CONFIGURED, applied.vcsInspection?.repositoryStatuses?.single()?.status)
  }

  private fun createWorkspace(
    name: String,
    repositories: List<String>,
    createRepositories: Boolean = true,
  ): Path {
    val root = temporaryFolder.newFolder(name).toPath()
    if (createRepositories) {
      repositories.forEach { Files.createDirectory(root.resolve(it)) }
    }
    val manifest = ReqwsProjectDetector.manifestPath(root)
    Files.createDirectories(manifest.parent)
    val repositoryJson = repositories.mapIndexed { index, repository ->
      """
        {
          "catalogRepositoryId": "repo_$index",
          "name": "$repository",
          "url": "https://example.test/team/$repository.git",
          "defaultBranch": "main",
          "relativePath": "$repository"
        }
      """.trimIndent()
    }.joinToString(separator = ",")
    Files.writeString(
      manifest,
      """
        {
          "schemaVersion": 1,
          "id": "workspace_id",
          "name": "Feature Login",
          "featureBranch": "feature/login",
          "rootPath": "${escapeJson(root.toRealPath().toString())}",
          "workspaceFilePath": "${escapeJson(root.resolve("workspace.code-workspace").toString())}",
          "repositories": [$repositoryJson],
          "createdAt": "2026-08-14T00:00:00.000Z",
          "updatedAt": "2026-08-14T00:00:00.000Z"
        }
      """.trimIndent(),
    )
    return root
  }

  private fun escapeJson(value: String): String = value.replace("\\", "\\\\").replace("\"", "\\\"")
}
