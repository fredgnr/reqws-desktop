package com.reqws.goland.project

import com.intellij.openapi.progress.ProcessCanceledException
import com.reqws.goland.manifest.ManifestSnapshot
import com.reqws.goland.manifest.RepositoryAvailability
import com.reqws.goland.manifest.ResolvedRepository
import com.reqws.goland.manifest.WorkspaceManifest
import com.reqws.goland.manifest.WorkspaceRepository
import com.reqws.goland.projectmodel.ProjectModelApplyException
import com.reqws.goland.projectmodel.ProjectModelErrorCode
import com.reqws.goland.sync.SyncTrigger
import java.nio.file.Path
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ReqwsProjectionApplierTest {
  @Test
  fun `returns after the managed project model converges`() = runBlocking {
    val events = mutableListOf<String>()
    val snapshot = snapshot()
    val applier = ReqwsProjectionApplier(
      isTrusted = { true },
      projectModel = ProjectModelProjection { _, _ -> events.add("model") },
    )

    applier.apply(snapshot)

    assertEquals(listOf("model"), events)
  }

  @Test
  fun `disables another roots notification for a bounded project-model follow-up`() = runBlocking {
    var notificationAllowed: Boolean? = null
    val applier = ReqwsProjectionApplier(
      isTrusted = { true },
      projectModel = ProjectModelProjection { _, allowRootsChangeNotification ->
        notificationAllowed = allowRootsChangeNotification
      },
    )

    applier.apply(snapshot(), SyncTrigger.PROJECT_MODEL_FOLLOW_UP)

    assertEquals(false, notificationAllowed)
  }

  @Test
  fun `maps a stale live file index to a layered project-content diagnostic`() {
    val applier = ReqwsProjectionApplier(
      isTrusted = { true },
      projectModel = ProjectModelProjection { _, _ ->
        throw ProjectModelApplyException(
          ProjectModelErrorCode.LIVE_FILE_INDEX_NOT_CONVERGED,
          "Live ProjectFileIndex did not converge.",
        )
      },
    )

    val failure = assertThrows(ReqwsProjectionApplyException::class.java) {
      runBlocking { applier.apply(snapshot()) }
    }

    assertEquals(ReqwsStableErrorCode.PROJECT_CONTENT_NOT_CONVERGED, failure.stableCode)
    assertEquals("PROJECT_FILE_INDEX", failure.field)
    assertTrue(failure.degraded)
  }

  @Test
  fun `keeps the Go Modules registry failure layer in diagnostics`() {
    val applier = ReqwsProjectionApplier(
      isTrusted = { true },
      projectModel = ProjectModelProjection { _, _ ->
        throw ProjectModelApplyException(
          ProjectModelErrorCode.GO_MODULES_REGISTRY_NOT_CONVERGED,
          "Go Modules registry did not converge.",
        )
      },
    )

    val failure = assertThrows(ReqwsProjectionApplyException::class.java) {
      runBlocking { applier.apply(snapshot()) }
    }

    assertEquals(ReqwsStableErrorCode.PROJECT_CONTENT_NOT_CONVERGED, failure.stableCode)
    assertEquals("GO_MODULES_REGISTRY", failure.field)
  }

  @Test
  fun `does not apply the project model while Safe Mode blocks the project`() {
    var sideEffects = 0
    val applier = ReqwsProjectionApplier(
      isTrusted = { false },
      projectModel = ProjectModelProjection { _, _ -> sideEffects += 1 },
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
      projectModel = ProjectModelProjection { _, _ -> sideEffects += 1 },
    )

    assertThrows(ReqwsProjectionApplyException::class.java) {
      runBlocking { applier.apply(snapshot()) }
    }

    assertEquals(0, sideEffects)
  }

  @Test
  fun `rejects service disposal that follows the model commit`() {
    var disposed = false
    val applier = ReqwsProjectionApplier(
      isTrusted = { true },
      isProjectDisposed = { disposed },
      projectModel = ProjectModelProjection { _, _ -> disposed = true },
    )

    assertThrows(ReqwsProjectionApplyException::class.java) {
      runBlocking { applier.apply(snapshot()) }
    }

  }

  @Test
  fun `propagates coroutine cancellation from the project model`() {
    val cancellation = CancellationException("cancel project model apply")
    val applier = ReqwsProjectionApplier(
      isTrusted = { true },
      projectModel = ProjectModelProjection { _, _ -> throw cancellation },
    )

    val thrown = assertThrows(CancellationException::class.java) {
      runBlocking { applier.apply(snapshot()) }
    }

    assertSame(cancellation, thrown)
  }

  @Test
  fun `propagates process cancellation from the project model`() {
    val cancellation = ProcessCanceledException()
    val applier = ReqwsProjectionApplier(
      isTrusted = { true },
      projectModel = ProjectModelProjection { _, _ -> throw cancellation },
    )

    val thrown = assertThrows(ProcessCanceledException::class.java) {
      runBlocking { applier.apply(snapshot()) }
    }

    assertSame(cancellation, thrown)
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
