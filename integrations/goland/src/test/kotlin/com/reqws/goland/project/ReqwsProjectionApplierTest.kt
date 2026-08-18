package com.reqws.goland.project

import com.reqws.goland.manifest.ManifestSnapshot
import com.reqws.goland.manifest.RepositoryAvailability
import com.reqws.goland.manifest.ResolvedRepository
import com.reqws.goland.manifest.WorkspaceManifest
import com.reqws.goland.manifest.WorkspaceRepository
import com.reqws.goland.vcs.VcsMappingApplyResult
import com.reqws.goland.vcs.VcsMappingDiagnostic
import com.reqws.goland.vcs.VcsMappingDiagnosticCode
import com.reqws.goland.vcs.VcsMappingPlan
import java.nio.file.Path
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class ReqwsProjectionApplierTest {
  @Test
  fun `advances digest only after model and VCS fully converge`() = runBlocking {
    val events = mutableListOf<String>()
    val snapshot = snapshot()
    val applier = ReqwsProjectionApplier(
      isTrusted = { true },
      projectModel = ProjectModelProjection { events.add("model") },
      vcsMappings = VcsMappingProjection {
        events.add("vcs")
        vcsResult()
      },
      digestRecorder = AppliedDigestRecorder { events.add("digest:$it") },
    )

    applier.apply(snapshot)

    assertEquals(listOf("model", "vcs", "digest:${snapshot.digestSha256}"), events)
  }

  @Test
  fun `keeps digest pending after a partial missing-repository projection`() {
    var digestRecorded = false
    val applier = ReqwsProjectionApplier(
      isTrusted = { true },
      projectModel = ProjectModelProjection {},
      vcsMappings = VcsMappingProjection {
        vcsResult(VcsMappingDiagnostic(VcsMappingDiagnosticCode.REPOSITORY_MISSING, 0))
      },
      digestRecorder = AppliedDigestRecorder { digestRecorded = true },
    )

    val failure = assertThrows(ReqwsProjectionApplyException::class.java) {
      runBlocking { applier.apply(snapshot()) }
    }

    assertEquals("REPOSITORY_MISSING", failure.stableCode)
    assertEquals(true, failure.degraded)
    assertEquals(false, digestRecorded)
  }

  @Test
  fun `does not call either adapter while Safe Mode blocks the project`() {
    var sideEffects = 0
    val applier = ReqwsProjectionApplier(
      isTrusted = { false },
      projectModel = ProjectModelProjection { sideEffects += 1 },
      vcsMappings = VcsMappingProjection {
        sideEffects += 1
        vcsResult()
      },
      digestRecorder = AppliedDigestRecorder { sideEffects += 1 },
    )

    val failure = assertThrows(ReqwsProjectionApplyException::class.java) {
      runBlocking { applier.apply(snapshot()) }
    }

    assertEquals(ReqwsStableErrorCode.SAFE_MODE_BLOCKED, failure.stableCode)
    assertEquals(0, sideEffects)
  }

  @Test
  fun `does not call either adapter after the project service is disposed`() {
    var sideEffects = 0
    val applier = ReqwsProjectionApplier(
      isTrusted = { true },
      isProjectDisposed = { true },
      projectModel = ProjectModelProjection { sideEffects += 1 },
      vcsMappings = VcsMappingProjection {
        sideEffects += 1
        vcsResult()
      },
      digestRecorder = AppliedDigestRecorder { sideEffects += 1 },
    )

    assertThrows(ReqwsProjectionApplyException::class.java) {
      runBlocking { applier.apply(snapshot()) }
    }

    assertEquals(0, sideEffects)
  }

  @Test
  fun `stops before VCS when service disposal follows the model commit`() {
    var disposed = false
    var vcsApplied = false
    var digestRecorded = false
    val applier = ReqwsProjectionApplier(
      isTrusted = { true },
      isProjectDisposed = { disposed },
      projectModel = ProjectModelProjection { disposed = true },
      vcsMappings = VcsMappingProjection {
        vcsApplied = true
        vcsResult()
      },
      digestRecorder = AppliedDigestRecorder { digestRecorded = true },
    )

    assertThrows(ReqwsProjectionApplyException::class.java) {
      runBlocking { applier.apply(snapshot()) }
    }

    assertEquals(false, vcsApplied)
    assertEquals(false, digestRecorded)
  }

  @Test
  fun `does not record a clean digest when disposal follows the VCS commit`() {
    var disposed = false
    var digestRecorded = false
    val applier = ReqwsProjectionApplier(
      isTrusted = { true },
      isProjectDisposed = { disposed },
      projectModel = ProjectModelProjection {},
      vcsMappings = VcsMappingProjection {
        disposed = true
        vcsResult()
      },
      digestRecorder = AppliedDigestRecorder { digestRecorded = true },
    )

    assertThrows(ReqwsProjectionApplyException::class.java) {
      runBlocking { applier.apply(snapshot()) }
    }

    assertEquals(false, digestRecorded)
  }

  private fun vcsResult(vararg diagnostics: VcsMappingDiagnostic) = VcsMappingApplyResult(
    plan = VcsMappingPlan(
      additions = emptyList(),
      removalIndices = emptySet(),
      nextOwnership = emptyList(),
      diagnostics = diagnostics.toList(),
    ),
    mappingsCommitted = false,
    ownershipCommitted = true,
    refreshed = true,
  )

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
