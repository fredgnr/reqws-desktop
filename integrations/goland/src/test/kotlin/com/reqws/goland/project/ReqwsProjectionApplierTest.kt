package com.reqws.goland.project

import com.reqws.goland.manifest.ManifestSnapshot
import com.reqws.goland.manifest.RepositoryAvailability
import com.reqws.goland.manifest.ResolvedRepository
import com.reqws.goland.manifest.WorkspaceManifest
import com.reqws.goland.manifest.WorkspaceRepository
import java.nio.file.Path
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class ReqwsProjectionApplierTest {
  @Test
  fun `advances digest after the managed project model converges`() = runBlocking {
    val events = mutableListOf<String>()
    val snapshot = snapshot()
    val applier = ReqwsProjectionApplier(
      isTrusted = { true },
      projectModel = ProjectModelProjection { events.add("model") },
      digestRecorder = AppliedDigestRecorder { events.add("digest:$it") },
    )

    applier.apply(snapshot)

    assertEquals(listOf("model", "digest:${snapshot.digestSha256}"), events)
  }

  @Test
  fun `does not apply the project model while Safe Mode blocks the project`() {
    var sideEffects = 0
    val applier = ReqwsProjectionApplier(
      isTrusted = { false },
      projectModel = ProjectModelProjection { sideEffects += 1 },
      digestRecorder = AppliedDigestRecorder { sideEffects += 1 },
    )

    val failure = assertThrows(ReqwsProjectionApplyException::class.java) {
      runBlocking { applier.apply(snapshot()) }
    }

    assertEquals(ReqwsStableErrorCode.SAFE_MODE_BLOCKED, failure.stableCode)
    assertEquals(0, sideEffects)
  }

  @Test
  fun `does not apply the project model after the project service is disposed`() {
    var sideEffects = 0
    val applier = ReqwsProjectionApplier(
      isTrusted = { true },
      isProjectDisposed = { true },
      projectModel = ProjectModelProjection { sideEffects += 1 },
      digestRecorder = AppliedDigestRecorder { sideEffects += 1 },
    )

    assertThrows(ReqwsProjectionApplyException::class.java) {
      runBlocking { applier.apply(snapshot()) }
    }

    assertEquals(0, sideEffects)
  }

  @Test
  fun `does not record a clean digest when service disposal follows the model commit`() {
    var disposed = false
    var digestRecorded = false
    val applier = ReqwsProjectionApplier(
      isTrusted = { true },
      isProjectDisposed = { disposed },
      projectModel = ProjectModelProjection { disposed = true },
      digestRecorder = AppliedDigestRecorder { digestRecorded = true },
    )

    assertThrows(ReqwsProjectionApplyException::class.java) {
      runBlocking { applier.apply(snapshot()) }
    }

    assertEquals(false, digestRecorded)
  }

  private fun snapshot(): ManifestSnapshot {
    val root = Path.of("/tmp/reqws-projection-test")
    val repository = WorkspaceRepository(
      catalogRepositoryId = "repo_api",
      name = "api",
      url = "https://example.invalid/api.git",
      defaultBranch = "main",
      relativePath = "api",
    )
    return ManifestSnapshot(
      manifest = WorkspaceManifest(
        schemaVersion = 1,
        id = "workspace_test",
        name = "Workspace",
        featureBranch = "feature/test",
        rootPath = root.toString(),
        workspaceFilePath = root.resolveSibling("workspace.code-workspace").toString(),
        repositories = listOf(repository),
        createdAt = "2026-08-14T00:00:00.000Z",
        updatedAt = "2026-08-14T00:00:00.000Z",
      ),
      manifestPath = root.resolve(".reqws/workspace.json"),
      canonicalProjectRoot = root,
      repositories = listOf(
        ResolvedRepository(
          repository = repository,
          path = root.resolve("api"),
          canonicalPath = root.resolve("api"),
          availability = RepositoryAvailability.PRESENT,
        ),
      ),
      digestSha256 = "a".repeat(64),
      diagnostics = emptyList(),
    )
  }
}
