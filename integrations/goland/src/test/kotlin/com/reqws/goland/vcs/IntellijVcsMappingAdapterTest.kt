package com.reqws.goland.vcs

import com.intellij.openapi.vcs.VcsDirectoryMapping
import com.intellij.openapi.vcs.VcsRootSettings
import com.reqws.goland.manifest.ManifestSnapshot
import com.reqws.goland.manifest.RepositoryAvailability
import com.reqws.goland.manifest.ResolvedRepository
import com.reqws.goland.manifest.WorkspaceManifest
import com.reqws.goland.manifest.WorkspaceRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.jdom.Element
import java.nio.file.Files
import java.nio.file.Path
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

class IntellijVcsMappingAdapterTest {
  @Rule
  @JvmField
  val temporaryFolder = TemporaryFolder()

  @Test
  fun `canonicalizes mappings with exact-directory last wins and natural sorting`() {
    val stale = VcsDirectoryMapping(
      "/workspace/repo-a",
      GIT_VCS_NAME,
      TestRootSettings("stale"),
    )
    val user = VcsDirectoryMapping("/z-user", "Mercurial")
    val winner = VcsDirectoryMapping(
      "/workspace/repo-a",
      GIT_VCS_NAME,
      TestRootSettings("winner"),
    )

    val canonical = canonicalizeVcsMappings(listOf(user, stale, winner))

    assertEquals(listOf("/workspace/repo-a", "/z-user"), canonical.map { it.directory })
    assertSame(winner, canonical.first())
    assertEquals(TestRootSettings("winner"), canonical.first().rootSettings)
  }

  @Test
  fun `commits merged mappings once preserves user entries and refreshes Git`() {
    val root = workspaceRoot()
    gitRepository(root, "repo-a")
    val userMapping = VcsDirectoryMapping(root.resolve("user").toString(), "Mercurial")
    val platform = FakePlatform(mappings = mutableListOf(userMapping))
    var recorded = emptyList<VcsMappingOwnership>()

    val result = IntellijVcsMappingAdapter(platform).apply(
      snapshot(root, listOf("repo-a")),
      emptyList(),
      ownershipRecorder { recorded = it },
    )

    assertTrue(result.mappingsCommitted)
    assertTrue(result.refreshed)
    assertEquals(1, platform.setCalls)
    assertEquals(1, platform.refreshCalls)
    assertTrue(platform.mappings.contains(userMapping))
    assertTrue(platform.mappings.any { it.directory == root.resolve("repo-a").toString() && it.vcs == GIT_VCS_NAME })
    assertEquals(VcsMappingOwnershipKind.CREATED, recorded.single().kind)
  }

  @Test
  fun `canonical platform ordering keeps reverse manifest additions created in one commit`() {
    val root = workspaceRoot()
    gitRepository(root, "repo-a")
    gitRepository(root, "repo-b")
    val platform = FakePlatform()
    var recorded = emptyList<VcsMappingOwnership>()

    IntellijVcsMappingAdapter(platform).apply(
      snapshot(root, listOf("repo-b", "repo-a")),
      emptyList(),
      ownershipRecorder { recorded = it },
    )

    assertEquals(1, platform.setCalls)
    assertEquals(
      listOf(root.resolve("repo-a").toString(), root.resolve("repo-b").toString()),
      platform.mappings.map { it.directory },
    )
    assertEquals(
      listOf(
        VcsMappingOwnership("repo-b", VcsMappingOwnershipKind.CREATED),
        VcsMappingOwnership("repo-a", VcsMappingOwnershipKind.CREATED),
      ),
      recorded,
    )
  }

  @Test
  fun `canonical platform ordering preserves a later user mapping and its root settings`() {
    val root = workspaceRoot()
    gitRepository(root, "repo-a")
    val settings = TestRootSettings("z-user-settings")
    val userMapping = VcsDirectoryMapping(
      root.resolveSibling("z-user").toString(),
      "Mercurial",
      settings,
    )
    val platform = FakePlatform(mappings = mutableListOf(userMapping))
    var recorded = emptyList<VcsMappingOwnership>()

    IntellijVcsMappingAdapter(platform).apply(
      snapshot(root, listOf("repo-a")),
      emptyList(),
      ownershipRecorder { recorded = it },
    )

    assertEquals(1, platform.setCalls)
    assertEquals(
      listOf(root.resolve("repo-a").toString(), userMapping.directory),
      platform.mappings.map { it.directory },
    )
    assertSame(settings, platform.mappings.single { it.directory == userMapping.directory }.rootSettings)
    assertEquals(
      listOf(VcsMappingOwnership("repo-a", VcsMappingOwnershipKind.CREATED)),
      recorded,
    )
  }

  @Test
  fun `records created ownership before a refresh failure`() {
    val root = workspaceRoot()
    gitRepository(root, "repo-a")
    val platform = FakePlatform(failRefresh = true)

    val failure = expectApplyFailure {
      IntellijVcsMappingAdapter(platform).apply(
        snapshot(root, listOf("repo-a")),
        emptyList(),
        VcsMappingOwnershipRecorder { preparedState ->
          platform.events.add("prepare")
          VcsMappingOwnershipCommit {
            if (preparedState.pendingAdds.isEmpty()) {
              assertEquals(VcsMappingOwnershipKind.CREATED, preparedState.stableMappings.single().kind)
            } else {
              assertTrue(preparedState.stableMappings.isEmpty())
              assertEquals("repo-a", preparedState.pendingAdds.single().relativeDirectory)
            }
            platform.events.add("ownership")
          }
        },
      )
    }

    assertEquals(
      listOf(
        "prepare",
        "write-context",
        "ownership",
        "write-context",
        "set",
        "write-context",
        "prepare",
        "ownership",
        "refresh",
      ),
      platform.events,
    )
    assertEquals(VcsMappingApplyStage.REFRESH, failure.stage)
    assertTrue(failure.mappingsCommitted)
    assertTrue(failure.ownershipCommitted)
  }

  @Test
  fun `mapping commit failure leaves only durable pending ownership`() {
    val root = workspaceRoot()
    gitRepository(root, "repo-a")
    val platform = FakePlatform(failSet = true)
    var recorderCalled = false

    val failure = expectApplyFailure {
      IntellijVcsMappingAdapter(platform).apply(
        snapshot(root, listOf("repo-a")),
        emptyList(),
        ownershipRecorder { recorderCalled = true },
      )
    }

    assertEquals(VcsMappingApplyStage.MAPPINGS, failure.stage)
    assertFalse(failure.mappingsCommitted)
    assertTrue(failure.ownershipCommitted)
    assertTrue(recorderCalled)
    assertEquals(0, platform.refreshCalls)
  }

  @Test
  fun `borrows an existing Git mapping without mutating mappings and still refreshes`() {
    val root = workspaceRoot()
    gitRepository(root, "repo-a")
    val existing = VcsDirectoryMapping(root.resolve("repo-a").toString(), GIT_VCS_NAME)
    val platform = FakePlatform(mappings = mutableListOf(existing))
    var recorded = emptyList<VcsMappingOwnership>()

    val result = IntellijVcsMappingAdapter(platform).apply(
      snapshot(root, listOf("repo-a")),
      emptyList(),
      ownershipRecorder { recorded = it },
    )

    assertFalse(result.mappingsCommitted)
    assertTrue(result.refreshed)
    assertEquals(0, platform.setCalls)
    assertEquals(1, platform.refreshCalls)
    assertEquals(VcsMappingOwnershipKind.BORROWED, recorded.single().kind)
  }

  @Test
  fun `an unchanged retry repeats refresh after a previous refresh failure`() {
    val root = workspaceRoot()
    gitRepository(root, "repo-a")
    val platform = FakePlatform(failRefresh = true)
    var ownership = emptyList<VcsMappingOwnership>()
    expectApplyFailure {
      IntellijVcsMappingAdapter(platform).apply(
        snapshot(root, listOf("repo-a")),
        ownership,
        ownershipRecorder { ownership = it },
      )
    }
    assertEquals(VcsMappingOwnershipKind.CREATED, ownership.single().kind)

    platform.failRefresh = false
    val retry = IntellijVcsMappingAdapter(platform).apply(
      snapshot(root, listOf("repo-a")),
      ownership,
      ownershipRecorder { ownership = it },
    )

    assertFalse(retry.mappingsCommitted)
    assertTrue(retry.refreshed)
    assertEquals(1, platform.setCalls)
    assertEquals(2, platform.refreshCalls)
    assertEquals(VcsMappingOwnershipKind.CREATED, ownership.single().kind)
  }

  @Test
  fun `skips missing and ordinary directories while mapping valid repositories`() {
    val root = workspaceRoot()
    gitRepository(root, "repo-ok")
    Files.createDirectory(root.resolve("ordinary"))
    val platform = FakePlatform()
    var recorded = emptyList<VcsMappingOwnership>()

    val result = IntellijVcsMappingAdapter(platform).apply(
      snapshot(root, listOf("repo-missing", "ordinary", "repo-ok")),
      emptyList(),
      ownershipRecorder { recorded = it },
    )

    assertEquals(listOf(root.resolve("repo-ok").toString()), result.plan.additions.map { it.directory })
    assertEquals(
      listOf(
        VcsMappingDiagnosticCode.REPOSITORY_MISSING,
        VcsMappingDiagnosticCode.REPOSITORY_NOT_GIT,
      ),
      result.plan.diagnostics.map { it.code },
    )
    assertEquals(listOf("repo-ok"), recorded.map { it.relativeDirectory })
  }

  @Test
  fun `replans when a customized desired mapping lands inside the first serialized commit attempt`() {
    val root = workspaceRoot()
    gitRepository(root, "repo-a")
    val userMapping = VcsDirectoryMapping(
      root.resolve("repo-a").toString(),
      GIT_VCS_NAME,
      TestRootSettings("user-customized"),
    )
    val platform = FakePlatform(
      onGet = { call, mappings ->
        if (call == 2) mappings.add(userMapping)
      },
    )
    var recorded = emptyList<VcsMappingOwnership>()

    IntellijVcsMappingAdapter(platform).apply(
      snapshot(root, listOf("repo-a")),
      emptyList(),
      ownershipRecorder { recorded = it },
    )

    assertEquals(3, platform.writeContextCalls)
    assertTrue(platform.getCalls >= 3)
    assertEquals(0, platform.setCalls)
    assertEquals(listOf(userMapping), platform.mappings)
    assertEquals(
      TestRootSettings("user-customized"),
      platform.mappings.single { it.directory == userMapping.directory }.rootSettings,
    )
    assertEquals(
      listOf(VcsMappingOwnership("repo-a", VcsMappingOwnershipKind.BORROWED)),
      recorded,
    )
  }

  @Test
  fun `serializes a user mapping write requested after the final read before plugin commit`() {
    val root = workspaceRoot()
    gitRepository(root, "repo-a")
    val userMapping = VcsDirectoryMapping(
      root.resolveSibling("user-root").toString(),
      "Mercurial",
      TestRootSettings("user-customized"),
    )
    val userWriteAttempted = CountDownLatch(1)
    val userWriteFinished = CountDownLatch(1)
    lateinit var platform: FakePlatform
    platform = FakePlatform(
      onAfterGet = { call, _ ->
        if (call == 3) {
          val userWriter = Thread({
            userWriteAttempted.countDown()
            platform.replaceMappingsAsUiWriter { current -> current + userMapping }
            userWriteFinished.countDown()
          }, "test-vcs-ui-writer").apply {
            isDaemon = true
          }
          userWriter.start()
          assertTrue(userWriteAttempted.await(5, TimeUnit.SECONDS))
          // The first serialized read is a preflight before the durable transition. This third
          // overall read is the second equality check immediately before set; the user writer
          // uses the same context and therefore cannot land in that final get/set window.
          val queueDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
          while (
            !platform.isMappingWriterQueued(userWriter) &&
            System.nanoTime() < queueDeadline
          ) {
            Thread.yield()
          }
          assertTrue(platform.isMappingWriterQueued(userWriter))
          assertEquals(1L, userWriteFinished.count)
        }
      },
    )
    var recorded = emptyList<VcsMappingOwnership>()

    IntellijVcsMappingAdapter(platform).apply(
      snapshot(root, listOf("repo-a")),
      emptyList(),
      ownershipRecorder { recorded = it },
    )

    assertTrue(userWriteFinished.await(5, TimeUnit.SECONDS))
    assertTrue(platform.mappings.contains(userMapping))
    assertTrue(platform.mappings.any {
      it.directory == root.resolve("repo-a").toString() && it.vcs == GIT_VCS_NAME
    })
    assertEquals(
      TestRootSettings("user-customized"),
      platform.mappings.single { it.directory == userMapping.directory }.rootSettings,
    )
    assertEquals(
      // The queued writer publishes an external event after ReqWS's set. Even though the event is
      // for another directory, the payload-less topic cannot prove the same-path plain mapping is
      // still the object ReqWS created, so deletion authority is conservatively revoked.
      listOf(VcsMappingOwnership("repo-a", VcsMappingOwnershipKind.BORROWED)),
      recorded,
    )
    assertTrue(platform.events.indexOf("set") < platform.events.indexOf("user-set"))
  }

  @Test
  fun `late pooled writer is detected and merged without losing its unknown customized mapping`() {
    val root = workspaceRoot()
    gitRepository(root, "repo-a")
    val userMapping = VcsDirectoryMapping(
      root.resolveSibling("late-user-root").toString(),
      "Mercurial",
      TestRootSettings("late-user-settings"),
    )
    val writerCaptured = CountDownLatch(1)
    val writerMayCommit = CountDownLatch(1)
    val writerFinished = CountDownLatch(1)
    lateinit var platform: FakePlatform
    platform = FakePlatform(
      onAfterGet = { call, snapshot ->
        if (call == 2) {
          val stale = snapshot.toList()
          Thread({
            writerCaptured.countDown()
            assertTrue(writerMayCommit.await(5, TimeUnit.SECONDS))
            platform.replaceMappingsAsPooledWriter(stale + userMapping)
            writerFinished.countDown()
          }, "late-vcs-pooled-writer").apply { isDaemon = true }.start()
        }
      },
      onSet = { writerMayCommit.countDown() },
      onAwaitQuiescence = { call ->
        if (call == 1) assertTrue(writerFinished.await(5, TimeUnit.SECONDS))
      },
    )
    val recorded = Collections.synchronizedList(mutableListOf<VcsMappingOwnershipState>())

    val result = IntellijVcsMappingAdapter(platform).apply(
      snapshot(root, listOf("repo-a")),
      emptyList(),
      VcsMappingOwnershipRecorder { state ->
        VcsMappingOwnershipCommit { recorded.add(state) }
      },
    )

    assertTrue(writerCaptured.await(5, TimeUnit.SECONDS))
    assertTrue(result.mappingsCommitted)
    assertTrue(platform.mappings.contains(userMapping))
    assertTrue(platform.mappings.any {
      it.directory == root.resolve("repo-a").toString() && it.vcs == GIT_VCS_NAME
    })
    assertEquals(
      TestRootSettings("late-user-settings"),
      platform.mappings.single { it.directory == userMapping.directory }.rootSettings,
    )
    assertEquals(
      listOf(VcsMappingOwnership("repo-a", VcsMappingOwnershipKind.CREATED)),
      recorded.last().stableMappings,
    )
    assertTrue(platform.setCalls >= 2)
  }

  @Test
  fun `pooled writer landing after pending persistence invalidates the plan before set and is merged`() {
    val root = workspaceRoot()
    gitRepository(root, "repo-a")
    val userMapping = VcsDirectoryMapping(
      root.resolveSibling("early-user-root").toString(),
      "Perforce",
      TestRootSettings("early-user-settings"),
    )
    val writerMayCommit = CountDownLatch(1)
    val writerFinished = CountDownLatch(1)
    lateinit var platform: FakePlatform
    platform = FakePlatform(
      onAfterGet = { call, snapshot ->
        if (call == 2) {
          val stale = snapshot.toList()
          Thread({
            assertTrue(writerMayCommit.await(5, TimeUnit.SECONDS))
            platform.replaceMappingsAsPooledWriter(stale + userMapping)
            writerFinished.countDown()
          }, "early-vcs-pooled-writer").apply { isDaemon = true }.start()
        }
      },
    )
    var releasedWriter = false
    val recorded = Collections.synchronizedList(mutableListOf<VcsMappingOwnershipState>())

    IntellijVcsMappingAdapter(platform).apply(
      snapshot(root, listOf("repo-a")),
      emptyList(),
      VcsMappingOwnershipRecorder { state ->
        VcsMappingOwnershipCommit {
          recorded.add(state)
          if (!releasedWriter && state.pendingAdds.isNotEmpty()) {
            releasedWriter = true
            writerMayCommit.countDown()
            assertTrue(writerFinished.await(5, TimeUnit.SECONDS))
          }
        }
      },
    )

    assertTrue(platform.mappings.contains(userMapping))
    assertTrue(platform.mappings.any {
      it.directory == root.resolve("repo-a").toString() && it.vcs == GIT_VCS_NAME
    })
    assertEquals(
      TestRootSettings("early-user-settings"),
      platform.mappings.single { it.directory == userMapping.directory }.rootSettings,
    )
    assertEquals(
      listOf(VcsMappingOwnership("repo-a", VcsMappingOwnershipKind.CREATED)),
      recorded.last().stableMappings,
    )
    assertTrue(platform.setCalls >= 1)
  }

  @Test
  fun `delayed equal external replacement demotes an addition to borrowed and never deletes it`() {
    val root = workspaceRoot()
    gitRepository(root, "repo-a")
    val externalReplacement = VcsDirectoryMapping(
      root.resolve("repo-a").toString(),
      GIT_VCS_NAME,
    )
    var externalReplacementLanded = false
    lateinit var platform: FakePlatform
    platform = FakePlatform(
      onSet = {
        if (!externalReplacementLanded) {
          externalReplacementLanded = true
          // This is intentionally a fresh, structurally equal mapping object. The event is
          // delayed until quiescence, so equality cannot prove that ReqWS still owns it.
          platform.replaceMappingsAsPooledWriterWithoutPublishing(listOf(externalReplacement))
        }
      },
      onAwaitQuiescence = { call ->
        if (call == 1) {
          platform.publishPooledWriterEventFromCurrent()
        }
      },
    )
    var ownership = emptyList<VcsMappingOwnership>()

    IntellijVcsMappingAdapter(platform).apply(
      snapshot(root, listOf("repo-a")),
      emptyList(),
      ownershipRecorder { ownership = it },
    )

    assertTrue(externalReplacementLanded)
    assertSame(externalReplacement, platform.mappings.single())
    assertEquals(
      listOf(VcsMappingOwnership("repo-a", VcsMappingOwnershipKind.BORROWED)),
      ownership,
    )
    assertEquals(1, platform.setCalls)

    IntellijVcsMappingAdapter(platform).apply(
      snapshot(root, emptyList()),
      ownership,
      ownershipRecorder { ownership = it },
    )

    assertSame(externalReplacement, platform.mappings.single())
    assertEquals(1, platform.setCalls)
    assertTrue(ownership.isEmpty())
  }

  @Test
  fun `equal external replacement durably revokes created ownership before acknowledgement`() {
    val root = workspaceRoot()
    Files.createDirectory(root.resolve(".idea"))
    gitRepository(root, "repo-a")
    val replacement = VcsDirectoryMapping(root.resolve("repo-a").toString(), GIT_VCS_NAME)
    val platform = FakePlatform(mappings = mutableListOf(replacement))
    platform.replaceMappingsAsPooledWriter(listOf(replacement))
    val ownershipState = ReqwsVcsOwnershipStateService()
    val committedStates = mutableListOf<VcsMappingOwnershipState>()

    IntellijVcsMappingAdapter(platform).apply(
      snapshot(root, listOf("repo-a")),
      listOf(VcsMappingOwnership("repo-a", VcsMappingOwnershipKind.CREATED)),
      VcsMappingOwnershipRecorder { state ->
        val commit = persistentRecorder(ownershipState, root).prepare(state)
        VcsMappingOwnershipCommit {
          commit.persistAndVerify()
          committedStates.add(state)
          platform.events.add("ownership")
        }
      },
    )

    assertEquals(0, platform.setCalls)
    assertSame(replacement, platform.mappings.single())
    assertEquals(
      listOf(VcsMappingOwnership("repo-a", VcsMappingOwnershipKind.BORROWED)),
      committedStates.first().stableMappings,
    )
    assertTrue(platform.events.indexOf("ownership") < platform.events.indexOf("external-ack"))
    assertEquals(
      listOf(VcsMappingOwnership("repo-a", VcsMappingOwnershipKind.BORROWED)),
      ReqwsVcsOwnershipStateService().readForProject(root).ownership,
    )
  }

  @Test
  fun `post-final external drift durably demotes created ownership before retry`() {
    val root = workspaceRoot()
    Files.createDirectory(root.resolve(".idea"))
    gitRepository(root, "repo-a")
    val mapping = VcsDirectoryMapping(root.resolve("repo-a").toString(), GIT_VCS_NAME)
    val externalReplacement = VcsDirectoryMapping(
      root.resolve("repo-a").toString(),
      GIT_VCS_NAME,
    )
    val disposed = AtomicBoolean(false)
    lateinit var platform: FakePlatform
    platform = FakePlatform(
      mappings = mutableListOf(mapping),
      onAwaitQuiescence = { call ->
        if (call == 2) platform.replaceMappingsAsPooledWriter(listOf(externalReplacement))
      },
    )
    val ownershipState = ReqwsVcsOwnershipStateService()
    var sawFinalCreated = false

    val failure = expectApplyFailure {
      IntellijVcsMappingAdapter(
        platform = platform,
        isProjectDisposed = disposed::get,
      ).apply(
        snapshot(root, listOf("repo-a")),
        listOf(VcsMappingOwnership("repo-a", VcsMappingOwnershipKind.CREATED)),
        VcsMappingOwnershipRecorder { state ->
          val commit = persistentRecorder(ownershipState, root).prepare(state)
          VcsMappingOwnershipCommit {
            commit.persistAndVerify()
            val kind = state.stableMappings.singleOrNull()?.kind
            if (kind == VcsMappingOwnershipKind.CREATED) {
              sawFinalCreated = true
            } else if (sawFinalCreated && kind == VcsMappingOwnershipKind.BORROWED) {
              // Stop before the retry can acknowledge the external event. The disk state must
              // already be non-authorizing at this exact crash/dispose boundary.
              disposed.set(true)
            }
          }
        },
      )
    }

    assertEquals(VcsMappingApplyErrorCode.PROJECT_DISPOSED, failure.code)
    assertTrue(sawFinalCreated)
    assertFalse(platform.events.contains("external-ack"))
    assertSame(externalReplacement, platform.mappings.single())
    assertEquals(
      listOf(VcsMappingOwnership("repo-a", VcsMappingOwnershipKind.BORROWED)),
      ReqwsVcsOwnershipStateService().readForProject(root).ownership,
    )
  }

  @Test
  fun `pending external baseline preserves an unpublished live-only mapping with root settings`() {
    val root = workspaceRoot()
    gitRepository(root, "repo-a")
    val baseMapping = VcsDirectoryMapping(root.resolveSibling("base-root").toString(), "Mercurial")
    val publishedUserMapping = VcsDirectoryMapping(
      root.resolveSibling("published-user-root").toString(),
      "Perforce",
      TestRootSettings("published-user-settings"),
    )
    val unpublishedSettings = TestRootSettings("unpublished-user-settings")
    val unpublishedUserMapping = VcsDirectoryMapping(
      root.resolveSibling("unpublished-user-root").toString(),
      "Perforce",
      unpublishedSettings,
    )
    val platform = FakePlatform(mappings = mutableListOf(baseMapping))
    platform.replaceMappingsAsPooledWriter(listOf(baseMapping, publishedUserMapping))
    platform.replaceMappingsAsPooledWriterWithoutPublishing(
      listOf(baseMapping, publishedUserMapping, unpublishedUserMapping),
    )
    var recorded = emptyList<VcsMappingOwnership>()

    IntellijVcsMappingAdapter(platform).apply(
      snapshot(root, listOf("repo-a")),
      emptyList(),
      ownershipRecorder { recorded = it },
    )

    assertTrue(platform.mappings.contains(baseMapping))
    assertTrue(platform.mappings.contains(publishedUserMapping))
    assertTrue(platform.mappings.contains(unpublishedUserMapping))
    assertSame(
      unpublishedSettings,
      platform.mappings.single { it.directory == unpublishedUserMapping.directory }.rootSettings,
    )
    assertTrue(platform.mappings.any {
      it.directory == root.resolve("repo-a").toString() && it.vcs == GIT_VCS_NAME
    })
    assertEquals(
      listOf(VcsMappingOwnership("repo-a", VcsMappingOwnershipKind.CREATED)),
      recorded,
    )
  }

  @Test
  fun `same-directory unpublished replacement waits for its event before any commit`() {
    val root = workspaceRoot()
    val directory = root.resolveSibling("shared-user-root").toString()
    val oldMapping = VcsDirectoryMapping(
      directory,
      "Perforce",
      TestRootSettings("old-user-settings"),
    )
    val newSettings = TestRootSettings("new-user-settings")
    val newMapping = VcsDirectoryMapping(directory, "Perforce", newSettings)
    lateinit var platform: FakePlatform
    platform = FakePlatform(
      mappings = mutableListOf(oldMapping),
      onAwaitQuiescence = { call ->
        if (call == 1) platform.publishPooledWriterEventFromCurrent()
      },
    )
    platform.replaceMappingsAsPooledWriter(listOf(oldMapping))
    platform.replaceMappingsAsPooledWriterWithoutPublishing(listOf(newMapping))
    var recorderCalled = false

    IntellijVcsMappingAdapter(platform).apply(
      snapshot(root, emptyList()),
      emptyList(),
      VcsMappingOwnershipRecorder { state ->
        VcsMappingOwnershipCommit {
          recorderCalled = true
          assertTrue(state.stableMappings.isEmpty())
          platform.events.add("ownership")
        }
      },
    )

    assertEquals(0, platform.setCalls)
    assertTrue(recorderCalled)
    assertSame(newSettings, platform.mappings.single().rootSettings)
    assertTrue(platform.events.indexOf("pooled-event") < platform.events.indexOf("ownership"))
  }

  @Test
  fun `same-directory unpublished replacement fails closed when its event never arrives`() {
    val root = workspaceRoot()
    val directory = root.resolveSibling("shared-user-root").toString()
    val oldMapping = VcsDirectoryMapping(
      directory,
      "Perforce",
      TestRootSettings("old-user-settings"),
    )
    val newMapping = VcsDirectoryMapping(
      directory,
      "Perforce",
      TestRootSettings("new-user-settings"),
    )
    val platform = FakePlatform(mappings = mutableListOf(oldMapping))
    platform.replaceMappingsAsPooledWriter(listOf(oldMapping))
    platform.replaceMappingsAsPooledWriterWithoutPublishing(listOf(newMapping))
    var recorderCalled = false

    val failure = expectApplyFailure {
      IntellijVcsMappingAdapter(platform).apply(
        snapshot(root, emptyList()),
        emptyList(),
        ownershipRecorder { recorderCalled = true },
      )
    }

    assertEquals(VcsMappingApplyStage.MAPPINGS, failure.stage)
    assertEquals(0, platform.setCalls)
    assertEquals(0, platform.refreshCalls)
    assertFalse(recorderCalled)
    assertEquals(5, platform.awaitQuiescenceCalls)
    assertEquals(listOf(newMapping), platform.mappings)
  }

  @Test
  fun `pending-only unpublished deletion fails closed without mapping ownership or refresh writes`() {
    val root = workspaceRoot()
    val baseMapping = VcsDirectoryMapping(root.resolveSibling("base-root").toString(), "Mercurial")
    val userMapping = VcsDirectoryMapping(
      root.resolveSibling("external-user-root").toString(),
      "Perforce",
      TestRootSettings("external-authoritative-settings"),
    )
    val platform = FakePlatform(mappings = mutableListOf(baseMapping, userMapping))
    platform.replaceMappingsAsPooledWriter(listOf(baseMapping, userMapping))
    platform.replaceMappingsAsPooledWriterWithoutPublishing(listOf(baseMapping))
    var recorderCalled = false

    val failure = expectApplyFailure {
      IntellijVcsMappingAdapter(platform).apply(
        snapshot(root, emptyList()),
        emptyList(),
        ownershipRecorder { recorderCalled = true },
      )
    }

    assertEquals(VcsMappingApplyStage.MAPPINGS, failure.stage)
    assertEquals(0, platform.setCalls)
    assertEquals(0, platform.refreshCalls)
    assertFalse(recorderCalled)
    assertEquals(5, platform.awaitQuiescenceCalls)
    assertEquals(listOf(baseMapping), platform.mappings)
  }

  @Test
  fun `cold pending add never authorizes deleting a same-path plain mapping`() {
    assertColdPendingAddDoesNotAuthorizeDeletion(customized = false)
  }

  @Test
  fun `cold pending add never authorizes deleting a same-path customized mapping`() {
    assertColdPendingAddDoesNotAuthorizeDeletion(customized = true)
  }

  @Test
  fun `fails without mapping or ownership writes when every serialized attempt is stale`() {
    val root = workspaceRoot()
    gitRepository(root, "repo-a")
    val platform = FakePlatform(
      onGet = { call, mappings ->
        if (call >= 2) {
          mappings.add(
            VcsDirectoryMapping(
              root.resolveSibling("user-root-$call").toString(),
              "Mercurial",
              TestRootSettings("user-$call"),
            ),
          )
        }
      },
    )
    var recorderCalled = false

    val failure = expectApplyFailure {
      IntellijVcsMappingAdapter(platform).apply(
        snapshot(root, listOf("repo-a")),
        emptyList(),
        ownershipRecorder { recorderCalled = true },
      )
    }

    assertEquals(VcsMappingApplyStage.MAPPINGS, failure.stage)
    assertEquals(5, platform.writeContextCalls)
    assertEquals(0, platform.setCalls)
    assertEquals(0, platform.refreshCalls)
    assertFalse(recorderCalled)
    assertEquals(5, platform.mappings.size)
    assertTrue(platform.mappings.all { it.rootSettings != null })
  }

  @Test
  fun `revokes created ownership before a destructive mapping removal`() {
    val root = workspaceRoot()
    val repository = root.resolve("repo-a")
    val platform = FakePlatform(
      mappings = mutableListOf(VcsDirectoryMapping(repository.toString(), GIT_VCS_NAME)),
      failSet = true,
    )
    var ownership = listOf(
      VcsMappingOwnership("repo-a", VcsMappingOwnershipKind.CREATED),
    )

    val failure = expectApplyFailure {
      IntellijVcsMappingAdapter(platform).apply(
        snapshot(root, emptyList()),
        ownership,
        VcsMappingOwnershipRecorder { preparedState ->
          platform.events.add("prepare")
          VcsMappingOwnershipCommit {
            ownership = preparedState.stableMappings
            platform.events.add("ownership")
          }
        },
      )
    }

    assertEquals(
      listOf("prepare", "write-context", "ownership", "write-context", "set"),
      platform.events,
    )
    assertEquals(VcsMappingApplyStage.MAPPINGS, failure.stage)
    assertFalse(failure.mappingsCommitted)
    assertTrue(failure.ownershipCommitted)
    assertTrue(ownership.isEmpty())
    assertEquals(1, platform.mappings.size)

    platform.failSet = false
    val retry = IntellijVcsMappingAdapter(platform).apply(
      snapshot(root, emptyList()),
      ownership,
      ownershipRecorder { ownership = it },
    )

    assertFalse(retry.mappingsCommitted)
    assertTrue(platform.mappings.any { it.directory == repository.toString() })
    assertTrue(retry.plan.diagnostics.any {
      it.code == VcsMappingDiagnosticCode.OWNERSHIP_CONFLICT
    })
  }

  @Test
  fun `cold pending removal never deletes a same-path user mapping`() {
    val root = workspaceRoot()
    Files.createDirectory(root.resolve(".idea"))
    val repository = root.resolve("repo-a")
    Files.createDirectory(repository)
    val ownershipState = ReqwsVcsOwnershipStateService()
    ownershipState.persistPreparedReplacement(
      ownershipState.prepareReplacementForProject(
        root,
        VcsMappingOwnershipState(
          stableMappings = emptyList(),
          pendingRemovals = listOf(
            VcsMappingPendingOwnership(
              relativeDirectory = "repo-a",
              operationToken = "0123456789abcdef0123456789abcdef",
            ),
          ),
        ),
      ),
    )
    val cold = ReqwsVcsOwnershipStateService()
    val loaded = cold.readForProject(root)
    val userMapping = VcsDirectoryMapping(repository.toString(), GIT_VCS_NAME)
    val platform = FakePlatform(mappings = mutableListOf(userMapping))

    val result = IntellijVcsMappingAdapter(platform).apply(
      snapshot(root, emptyList()),
      loaded.ownership,
      persistentRecorder(cold, root),
    )

    assertEquals(listOf(userMapping), platform.mappings)
    assertEquals(0, platform.setCalls)
    assertTrue(result.plan.diagnostics.any {
      it.code == VcsMappingDiagnosticCode.OWNERSHIP_CONFLICT
    })
    assertTrue(cold.readForProject(root).ownership.isEmpty())
    assertTrue(cold.readForProject(root).pendingRemovals.isEmpty())
  }

  @Test
  fun `cold pending removal re-add is borrowed and preserves user root settings`() {
    val root = workspaceRoot()
    Files.createDirectory(root.resolve(".idea"))
    gitRepository(root, "repo-a")
    val ownershipState = ReqwsVcsOwnershipStateService()
    ownershipState.persistPreparedReplacement(
      ownershipState.prepareReplacementForProject(
        root,
        VcsMappingOwnershipState(
          stableMappings = emptyList(),
          pendingRemovals = listOf(
            VcsMappingPendingOwnership(
              relativeDirectory = "repo-a",
              operationToken = "0123456789abcdef0123456789abcdef",
            ),
          ),
        ),
      ),
    )
    val cold = ReqwsVcsOwnershipStateService()
    val loaded = cold.readForProject(root)
    val customized = VcsDirectoryMapping(
      root.resolve("repo-a").toString(),
      GIT_VCS_NAME,
      TestRootSettings("user-readded"),
    )
    val platform = FakePlatform(mappings = mutableListOf(customized))

    IntellijVcsMappingAdapter(platform).apply(
      snapshot(root, listOf("repo-a")),
      loaded.ownership,
      persistentRecorder(cold, root),
    )

    assertEquals(listOf(customized), platform.mappings)
    assertEquals(0, platform.setCalls)
    assertEquals(
      listOf(VcsMappingOwnership("repo-a", VcsMappingOwnershipKind.BORROWED)),
      cold.readForProject(root).ownership,
    )
  }

  @Test
  fun `does not revoke ownership after trust changes during mapping planning`() {
    val root = workspaceRoot()
    val repository = root.resolve("repo-a")
    var trusted = true
    val platform = FakePlatform(
      mappings = mutableListOf(VcsDirectoryMapping(repository.toString(), GIT_VCS_NAME)),
      onGet = { call, _ ->
        if (call == 2) trusted = false
      },
    )
    var recorderCalled = false

    val failure = expectApplyFailure {
      IntellijVcsMappingAdapter(
        platform = platform,
        isProjectTrusted = { trusted },
      ).apply(
        snapshot(root, emptyList()),
        listOf(VcsMappingOwnership("repo-a", VcsMappingOwnershipKind.CREATED)),
        ownershipRecorder { recorderCalled = true },
      )
    }

    assertEquals(VcsMappingApplyErrorCode.SAFE_MODE_BLOCKED, failure.code)
    assertEquals(VcsMappingApplyStage.OWNERSHIP, failure.stage)
    assertFalse(failure.mappingsCommitted)
    assertFalse(failure.ownershipCommitted)
    assertFalse(recorderCalled)
    assertEquals(0, platform.setCalls)
    assertEquals(0, platform.refreshCalls)
    assertEquals(listOf(VcsDirectoryMapping(repository.toString(), GIT_VCS_NAME)), platform.mappings)
  }

  @Test
  fun `does not remove a mapping after disposal follows ownership revocation`() {
    val root = workspaceRoot()
    val repository = root.resolve("repo-a")
    var disposed = false
    var ownership = listOf(VcsMappingOwnership("repo-a", VcsMappingOwnershipKind.CREATED))
    val platform = FakePlatform(
      mappings = mutableListOf(VcsDirectoryMapping(repository.toString(), GIT_VCS_NAME)),
    )

    val failure = expectApplyFailure {
      IntellijVcsMappingAdapter(
        platform = platform,
        isProjectDisposed = { disposed },
      ).apply(
        snapshot(root, emptyList()),
        ownership,
        ownershipRecorder {
          ownership = it
          disposed = true
          platform.events.add("ownership")
        },
      )
    }

    assertEquals(VcsMappingApplyErrorCode.PROJECT_DISPOSED, failure.code)
    assertEquals(VcsMappingApplyStage.MAPPINGS, failure.stage)
    assertFalse(failure.mappingsCommitted)
    assertTrue(failure.ownershipCommitted)
    assertTrue(ownership.isEmpty())
    assertEquals(listOf("write-context", "ownership", "write-context"), platform.events)
    assertEquals(0, platform.setCalls)
    assertEquals(0, platform.refreshCalls)
    assertEquals(listOf(VcsDirectoryMapping(repository.toString(), GIT_VCS_NAME)), platform.mappings)
  }

  @Test
  fun `does not record deletion authority after trust changes following a mapping commit`() {
    val root = workspaceRoot()
    gitRepository(root, "repo-a")
    var trusted = true
    var recorderCalled = false
    val platform = FakePlatform(onSet = { trusted = false })

    val failure = expectApplyFailure {
      IntellijVcsMappingAdapter(
        platform = platform,
        isProjectTrusted = { trusted },
      ).apply(
        snapshot(root, listOf("repo-a")),
        emptyList(),
        ownershipRecorder { recorderCalled = true },
      )
    }

    assertEquals(VcsMappingApplyErrorCode.SAFE_MODE_BLOCKED, failure.code)
    assertEquals(VcsMappingApplyStage.OWNERSHIP, failure.stage)
    assertTrue(failure.mappingsCommitted)
    assertTrue(failure.ownershipCommitted)
    assertTrue(recorderCalled)
    assertEquals(1, platform.setCalls)
    assertEquals(0, platform.refreshCalls)
    assertTrue(platform.mappings.any { it.directory == root.resolve("repo-a").toString() })
  }

  @Test
  fun `never deletes an owned mapping after user root settings are added`() {
    val root = workspaceRoot()
    val repository = root.resolve("repo-a")
    val customized = VcsDirectoryMapping(
      repository.toString(),
      GIT_VCS_NAME,
      TestRootSettings("customized"),
    )
    val platform = FakePlatform(mappings = mutableListOf(customized))
    var ownership = listOf(VcsMappingOwnership("repo-a", VcsMappingOwnershipKind.CREATED))

    val result = IntellijVcsMappingAdapter(platform).apply(
      snapshot(root, emptyList()),
      ownership,
      ownershipRecorder { ownership = it },
    )

    assertFalse(result.mappingsCommitted)
    assertEquals(0, platform.setCalls)
    assertEquals(listOf(customized), platform.mappings)
    assertTrue(ownership.isEmpty())
    assertTrue(result.plan.diagnostics.any {
      it.code == VcsMappingDiagnosticCode.OWNERSHIP_CONFLICT
    })
  }

  @Test
  fun `replans when only user root settings change between stability reads`() {
    val root = workspaceRoot()
    gitRepository(root, "repo-a")
    val userRoot = root.resolveSibling("user-root").toString()
    val original = VcsDirectoryMapping(userRoot, "Mercurial", TestRootSettings("original"))
    val updated = VcsDirectoryMapping(userRoot, "Mercurial", TestRootSettings("updated"))
    val platform = FakePlatform(
      mappings = mutableListOf(original),
      onGet = { call, mappings ->
        if (call == 2) {
          mappings.clear()
          mappings.add(updated)
        }
      },
    )

    IntellijVcsMappingAdapter(platform).apply(
      snapshot(root, listOf("repo-a")),
      emptyList(),
      ownershipRecorder {},
    )

    assertTrue(platform.mappings.contains(updated))
    assertFalse(platform.mappings.contains(original))
    assertTrue(platform.getCalls >= 3)
  }

  @Test
  fun `reports unavailable Git without reading or changing mappings`() {
    val root = workspaceRoot()
    gitRepository(root, "repo-a")
    val platform = FakePlatform(gitAvailable = false)

    val failure = expectApplyFailure {
      IntellijVcsMappingAdapter(platform).apply(
        snapshot(root, listOf("repo-a")),
        emptyList(),
        ownershipRecorder {},
      )
    }

    assertEquals(VcsMappingApplyErrorCode.GIT_PLUGIN_UNAVAILABLE, failure.code)
    assertEquals(VcsMappingApplyStage.AVAILABILITY, failure.stage)
    assertEquals(0, platform.getCalls)
  }

  private fun workspaceRoot(): Path = temporaryFolder.newFolder().toPath().toRealPath()

  private fun assertColdPendingAddDoesNotAuthorizeDeletion(customized: Boolean) {
    val root = workspaceRoot()
    Files.createDirectory(root.resolve(".idea"))
    gitRepository(root, "repo-a")
    val state = ReqwsVcsOwnershipStateService()
    state.persistPreparedReplacement(
      state.prepareReplacementForProject(
        root,
        VcsMappingOwnershipState(
          stableMappings = emptyList(),
          pendingAdds = listOf(
            VcsMappingPendingOwnership(
              relativeDirectory = "repo-a",
              operationToken = "0123456789abcdef0123456789abcdef",
            ),
          ),
        ),
      ),
    )
    val cold = ReqwsVcsOwnershipStateService()
    val loaded = cold.readForProject(root)
    val settings = if (customized) TestRootSettings("user-readded") else null
    val userMapping = VcsDirectoryMapping(
      root.resolve("repo-a").toString(),
      GIT_VCS_NAME,
      settings,
    )
    val platform = FakePlatform(mappings = mutableListOf(userMapping))

    assertTrue(loaded.ownership.isEmpty())
    assertEquals(listOf("repo-a"), loaded.pendingAdds.map { it.relativeDirectory })
    IntellijVcsMappingAdapter(platform).apply(
      snapshot(root, emptyList()),
      loaded.ownership,
      persistentRecorder(cold, root),
    )
    assertEquals(listOf(userMapping), platform.mappings)
    assertEquals(0, platform.setCalls)
    if (settings != null) assertSame(settings, platform.mappings.single().rootSettings)
    assertTrue(cold.readForProject(root).ownership.isEmpty())
  }

  private fun gitRepository(root: Path, name: String) {
    Files.createDirectories(root.resolve(name).resolve(".git"))
  }

  private fun snapshot(root: Path, repositoryNames: List<String>): ManifestSnapshot {
    val repositories = repositoryNames.mapIndexed { index, name ->
      val repository = WorkspaceRepository(
        catalogRepositoryId = "repo_$index",
        name = name,
        url = "https://sensitive.invalid/repository.git?token=secret",
        defaultBranch = "main",
        relativePath = name,
      )
      val path = root.resolve(name)
      if (Files.isDirectory(path)) {
        ResolvedRepository(repository, path, path.toRealPath(), RepositoryAvailability.PRESENT)
      } else {
        ResolvedRepository(repository, path, null, RepositoryAvailability.MISSING)
      }
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

  private fun expectApplyFailure(block: () -> Unit): VcsMappingApplyException {
    try {
      block()
    } catch (exception: VcsMappingApplyException) {
      return exception
    }
    throw AssertionError("Expected VcsMappingApplyException")
  }
}

private fun ownershipRecorder(
  onCommit: (List<VcsMappingOwnership>) -> Unit,
): VcsMappingOwnershipRecorder = VcsMappingOwnershipRecorder { state ->
  VcsMappingOwnershipCommit { onCommit(state.stableMappings) }
}

private fun persistentRecorder(
  service: ReqwsVcsOwnershipStateService,
  root: Path,
): VcsMappingOwnershipRecorder = VcsMappingOwnershipRecorder { state ->
  VcsMappingOwnershipCommit {
    val replacement = service.prepareReplacementForProject(root, state)
    service.persistPreparedReplacement(replacement)
  }
}

private class FakePlatform(
  private val gitAvailable: Boolean = true,
  val mappings: MutableList<VcsDirectoryMapping> = mutableListOf(),
  var failSet: Boolean = false,
  var failRefresh: Boolean = false,
  private val onGet: ((Int, MutableList<VcsDirectoryMapping>) -> Unit)? = null,
  private val onAfterGet: ((Int, List<VcsDirectoryMapping>) -> Unit)? = null,
  private val onBeforeSet: (() -> Unit)? = null,
  private val onSet: (() -> Unit)? = null,
  private val onAwaitQuiescence: ((Int) -> Unit)? = null,
) : VcsMappingPlatform {
  var getCalls = 0
  var setCalls = 0
  var refreshCalls = 0
  var writeContextCalls = 0
  var awaitQuiescenceCalls = 0
  val events: MutableList<String> = Collections.synchronizedList(mutableListOf())
  private val mappingsWriteContextLock = ReentrantLock()
  private val revision = AtomicLong(0)
  private val pendingExternal = AtomicReference<ExternalVcsMappings?>(null)

  init {
    val canonical = canonicalizeVcsMappings(mappings)
    mappings.clear()
    mappings.addAll(canonical)
  }

  override fun isGitAvailable(): Boolean = gitAvailable

  override fun getDirectoryMappings(): List<VcsDirectoryMapping> {
    getCalls += 1
    val result = synchronized(mappings) {
      onGet?.invoke(getCalls, mappings)
      canonicalizeVcsMappings(mappings.toList())
    }
    onAfterGet?.invoke(getCalls, result)
    return result
  }

  override fun getVersionedDirectoryMappings(): VersionedVcsMappings = VersionedVcsMappings(
    revision = revision.get(),
    mappings = getDirectoryMappings(),
    pendingExternal = pendingExternal.get()?.platformCanonicalized(),
  ).platformCanonicalized()

  override fun awaitQuiescentDirectoryMappings(): VersionedVcsMappings {
    awaitQuiescenceCalls += 1
    onAwaitQuiescence?.invoke(awaitQuiescenceCalls)
    return getVersionedDirectoryMappings()
  }

  override fun acknowledgeExternalMappings(revision: Long) {
    while (true) {
      val pending = pendingExternal.get() ?: return
      if (pending.revision != revision) return
      if (pendingExternal.compareAndSet(pending, null)) {
        events.add("external-ack")
        return
      }
    }
  }

  override fun setDirectoryMappings(mappings: List<VcsDirectoryMapping>) {
    setCalls += 1
    events.add("set")
    if (failSet) throw IllegalStateException("set failed")
    onBeforeSet?.invoke()
    val canonical = canonicalizeVcsMappings(mappings)
    synchronized(this.mappings) {
      this.mappings.clear()
      this.mappings.addAll(canonical)
    }
    revision.incrementAndGet()
    onSet?.invoke()
  }

  override fun runInDirectoryMappingsWriteContext(action: () -> Unit) {
    writeContextCalls += 1
    mappingsWriteContextLock.withLock {
      events.add("write-context")
      action()
    }
  }

  fun isMappingWriterQueued(thread: Thread): Boolean =
    mappingsWriteContextLock.hasQueuedThread(thread)

  fun replaceMappingsAsUiWriter(transform: (List<VcsDirectoryMapping>) -> List<VcsDirectoryMapping>) {
    runInDirectoryMappingsWriteContext {
      val next = canonicalizeVcsMappings(transform(canonicalizeVcsMappings(mappings.toList())))
      synchronized(mappings) {
        mappings.clear()
        mappings.addAll(next)
      }
      val externalRevision = revision.incrementAndGet()
      pendingExternal.set(ExternalVcsMappings(externalRevision, next).platformCanonicalized())
      events.add("user-set")
    }
  }

  fun replaceMappingsAsPooledWriter(mappings: List<VcsDirectoryMapping>) {
    replaceMappingsAsPooledWriterWithoutPublishing(mappings)
    publishPooledWriterEventFromCurrent()
  }

  fun replaceMappingsAsPooledWriterWithoutPublishing(mappings: List<VcsDirectoryMapping>) {
    val canonical = canonicalizeVcsMappings(mappings)
    synchronized(this.mappings) {
      this.mappings.clear()
      this.mappings.addAll(canonical)
      events.add("pooled-set")
    }
  }

  fun publishPooledWriterEventFromCurrent() {
    synchronized(mappings) {
      val externalRevision = revision.incrementAndGet()
      pendingExternal.set(
        ExternalVcsMappings(
          externalRevision,
          canonicalizeVcsMappings(mappings.toList()),
        ),
      )
      events.add("pooled-event")
    }
  }

  override fun refreshGitRepositories() {
    refreshCalls += 1
    events.add("refresh")
    if (failRefresh) throw IllegalStateException("refresh failed")
  }
}

private data class TestRootSettings(private val id: String) : VcsRootSettings {
  override fun readExternal(element: Element) = Unit

  override fun writeExternal(element: Element) = Unit
}
