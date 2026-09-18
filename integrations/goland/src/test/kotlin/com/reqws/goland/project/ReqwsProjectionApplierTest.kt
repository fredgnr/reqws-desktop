package com.reqws.goland.project

import com.intellij.openapi.progress.ProcessCanceledException
import com.reqws.goland.manifest.ManifestSnapshot
import com.reqws.goland.manifest.RepositoryAvailability
import com.reqws.goland.manifest.ResolvedRepository
import com.reqws.goland.manifest.WorkspaceManifest
import com.reqws.goland.manifest.WorkspaceRepository
import com.reqws.goland.projectmodel.ProjectModelApplyException
import com.reqws.goland.projectmodel.ProjectModelErrorCode
import com.reqws.goland.sync.LatestWinsSyncCoordinator
import com.reqws.goland.sync.SyncCandidate
import com.reqws.goland.sync.SyncCandidateApplier
import com.reqws.goland.sync.SyncCandidateCommitter
import com.reqws.goland.sync.SyncCoordinatorEvent
import com.reqws.goland.sync.SyncCoordinatorObserver
import com.reqws.goland.sync.SyncTrigger
import java.nio.file.Path
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
      projectModel = ProjectModelProjection { _ -> events.add("model") },
    )

    applier.apply(snapshot)

    assertEquals(listOf("model"), events)
  }

  @Test
  fun `maps a stale live file index to a layered project-content diagnostic`() {
    val applier = ReqwsProjectionApplier(
      isTrusted = { true },
      projectModel = ProjectModelProjection { _ ->
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
  fun `file index failure dirties the same digest until a successful retry commits it`() = runBlocking {
    val snapshot = snapshot()
    val events = Channel<SyncCoordinatorEvent>(Channel.UNLIMITED)
    var fileIndexConverged = true
    var applyCount = 0
    var commitCount = 0
    val projectionApplier = ReqwsProjectionApplier(
      isTrusted = { true },
      projectModel = ProjectModelProjection { _ ->
        applyCount += 1
        if (!fileIndexConverged) {
          throw ProjectModelApplyException(
            ProjectModelErrorCode.LIVE_FILE_INDEX_NOT_CONVERGED,
            "Live ProjectFileIndex did not converge.",
          )
        }
      },
    )
    val coordinator = LatestWinsSyncCoordinator(
      scope = this,
      applier = SyncCandidateApplier<ManifestSnapshot> { projectionApplier.apply(it.value) },
      committer = SyncCandidateCommitter { commitCount += 1 },
      observer = SyncCoordinatorObserver { events.trySend(it) },
    )
    val candidate = SyncCandidate(snapshot.digestSha256, snapshot)
    suspend fun nextOutcome(): SyncCoordinatorEvent = withTimeout(5_000) {
      var event = events.receive()
      while (event is SyncCoordinatorEvent.Applying) event = events.receive()
      event
    }
    try {
      assertTrue(coordinator.offer(candidate))
      assertTrue(nextOutcome() is SyncCoordinatorEvent.Applied)
      assertEquals(snapshot.digestSha256, coordinator.lastAppliedDigest)
      assertEquals(1, commitCount)

      fileIndexConverged = false
      assertTrue(coordinator.offer(candidate, SyncTrigger.MANUAL))
      val failed = nextOutcome() as SyncCoordinatorEvent.Failed
      val failure = failed.cause as ReqwsProjectionApplyException
      assertEquals(ReqwsStableErrorCode.PROJECT_CONTENT_NOT_CONVERGED, failure.stableCode)
      assertEquals("PROJECT_FILE_INDEX", failure.field)
      assertTrue(failure.degraded)
      assertNull(coordinator.lastAppliedDigest)
      assertEquals(1, commitCount)

      fileIndexConverged = true
      assertTrue(coordinator.offer(candidate))
      assertTrue(nextOutcome() is SyncCoordinatorEvent.Applied)
      assertEquals(snapshot.digestSha256, coordinator.lastAppliedDigest)
      assertEquals(2, commitCount)
      assertEquals(3, applyCount)

      assertTrue(coordinator.offer(candidate))
      assertTrue(nextOutcome() is SyncCoordinatorEvent.NoOp)
      assertEquals(3, applyCount)
      assertEquals(2, commitCount)
    } finally {
      coordinator.close()
    }
  }

  @Test
  fun `classifies virgin project metadata readiness without reporting ownership conflict`() {
    val applier = ReqwsProjectionApplier(
      isTrusted = { true },
      projectModel = ProjectModelProjection { _ ->
        throw ProjectModelApplyException(
          ProjectModelErrorCode.PROJECT_METADATA_NOT_READY,
          "The .idea directory is not ready.",
        )
      },
    )

    val failure = assertThrows(ReqwsProjectionApplyException::class.java) {
      runBlocking { applier.apply(snapshot()) }
    }

    assertEquals(ReqwsStableErrorCode.PROJECT_MODEL_APPLY_FAILED, failure.stableCode)
    assertEquals(ReqwsProjectionRetryKind.PROJECT_METADATA_READINESS, failure.retryKind)
    assertEquals(false, failure.degraded)
  }

  @Test
  fun `does not apply the project model while Safe Mode blocks the project`() {
    var sideEffects = 0
    val applier = ReqwsProjectionApplier(
      isTrusted = { false },
      projectModel = ProjectModelProjection { _ -> sideEffects += 1 },
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
      projectModel = ProjectModelProjection { _ -> sideEffects += 1 },
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
      projectModel = ProjectModelProjection { _ -> disposed = true },
    )

    assertThrows(ReqwsProjectionApplyException::class.java) {
      runBlocking { applier.apply(snapshot()) }
    }
  }

  @Test
  fun `rejects trust revocation that follows the model commit`() {
    var trusted = true
    val applier = ReqwsProjectionApplier(
      isTrusted = { trusted },
      projectModel = ProjectModelProjection { _ -> trusted = false },
    )

    val failure = assertThrows(ReqwsProjectionApplyException::class.java) {
      runBlocking { applier.apply(snapshot()) }
    }

    assertEquals(ReqwsStableErrorCode.SAFE_MODE_BLOCKED, failure.stableCode)
  }

  @Test
  fun `propagates coroutine cancellation from the project model`() {
    val cancellation = CancellationException("cancel project model apply")
    val applier = ReqwsProjectionApplier(
      isTrusted = { true },
      projectModel = ProjectModelProjection { _ -> throw cancellation },
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
      projectModel = ProjectModelProjection { _ -> throw cancellation },
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
