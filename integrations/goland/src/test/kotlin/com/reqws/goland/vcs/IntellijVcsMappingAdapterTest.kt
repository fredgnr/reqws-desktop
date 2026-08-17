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
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.jdom.Element
import java.nio.file.Files
import java.nio.file.Path

class IntellijVcsMappingAdapterTest {
  @Rule
  @JvmField
  val temporaryFolder = TemporaryFolder()

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
      VcsMappingOwnershipRecorder { recorded = it },
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
  fun `records created ownership before a refresh failure`() {
    val root = workspaceRoot()
    gitRepository(root, "repo-a")
    val platform = FakePlatform(failRefresh = true)

    val failure = expectApplyFailure {
      IntellijVcsMappingAdapter(platform).apply(
        snapshot(root, listOf("repo-a")),
        emptyList(),
        VcsMappingOwnershipRecorder {
          assertEquals(VcsMappingOwnershipKind.CREATED, it.single().kind)
          platform.events.add("ownership")
        },
      )
    }

    assertEquals(listOf("set", "ownership", "refresh"), platform.events)
    assertEquals(VcsMappingApplyStage.REFRESH, failure.stage)
    assertTrue(failure.mappingsCommitted)
    assertTrue(failure.ownershipCommitted)
  }

  @Test
  fun `does not record ownership when the mapping commit fails`() {
    val root = workspaceRoot()
    gitRepository(root, "repo-a")
    val platform = FakePlatform(failSet = true)
    var recorderCalled = false

    val failure = expectApplyFailure {
      IntellijVcsMappingAdapter(platform).apply(
        snapshot(root, listOf("repo-a")),
        emptyList(),
        VcsMappingOwnershipRecorder { recorderCalled = true },
      )
    }

    assertEquals(VcsMappingApplyStage.MAPPINGS, failure.stage)
    assertFalse(failure.mappingsCommitted)
    assertFalse(recorderCalled)
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
      VcsMappingOwnershipRecorder { recorded = it },
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
        VcsMappingOwnershipRecorder { ownership = it },
      )
    }
    assertEquals(VcsMappingOwnershipKind.CREATED, ownership.single().kind)

    platform.failRefresh = false
    val retry = IntellijVcsMappingAdapter(platform).apply(
      snapshot(root, listOf("repo-a")),
      ownership,
      VcsMappingOwnershipRecorder { ownership = it },
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
      VcsMappingOwnershipRecorder { recorded = it },
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
  fun `replans against a user mapping added immediately before commit`() {
    val root = workspaceRoot()
    gitRepository(root, "repo-a")
    val userMapping = VcsDirectoryMapping(root.resolveSibling("user-root").toString(), "Mercurial")
    val platform = FakePlatform(
      onGet = { call, mappings ->
        if (call == 2) mappings.add(userMapping)
      },
    )

    IntellijVcsMappingAdapter(platform).apply(
      snapshot(root, listOf("repo-a")),
      emptyList(),
      VcsMappingOwnershipRecorder {},
    )

    assertTrue(platform.mappings.contains(userMapping))
    assertTrue(platform.mappings.any { it.directory == root.resolve("repo-a").toString() })
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
        VcsMappingOwnershipRecorder {
          ownership = it
          platform.events.add("ownership")
        },
      )
    }

    assertEquals(listOf("ownership", "set"), platform.events)
    assertEquals(VcsMappingApplyStage.MAPPINGS, failure.stage)
    assertFalse(failure.mappingsCommitted)
    assertTrue(failure.ownershipCommitted)
    assertTrue(ownership.isEmpty())
    assertEquals(1, platform.mappings.size)

    platform.failSet = false
    val retry = IntellijVcsMappingAdapter(platform).apply(
      snapshot(root, emptyList()),
      ownership,
      VcsMappingOwnershipRecorder { ownership = it },
    )

    assertFalse(retry.mappingsCommitted)
    assertTrue(platform.mappings.any { it.directory == repository.toString() })
    assertTrue(retry.plan.diagnostics.any {
      it.code == VcsMappingDiagnosticCode.OWNERSHIP_CONFLICT
    })
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
        VcsMappingOwnershipRecorder { recorderCalled = true },
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
        VcsMappingOwnershipRecorder {
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
    assertEquals(listOf("ownership"), platform.events)
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
        VcsMappingOwnershipRecorder { recorderCalled = true },
      )
    }

    assertEquals(VcsMappingApplyErrorCode.SAFE_MODE_BLOCKED, failure.code)
    assertEquals(VcsMappingApplyStage.OWNERSHIP, failure.stage)
    assertTrue(failure.mappingsCommitted)
    assertFalse(failure.ownershipCommitted)
    assertFalse(recorderCalled)
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
      VcsMappingOwnershipRecorder { ownership = it },
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
      VcsMappingOwnershipRecorder {},
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
        VcsMappingOwnershipRecorder {},
      )
    }

    assertEquals(VcsMappingApplyErrorCode.GIT_PLUGIN_UNAVAILABLE, failure.code)
    assertEquals(VcsMappingApplyStage.AVAILABILITY, failure.stage)
    assertEquals(0, platform.getCalls)
  }

  private fun workspaceRoot(): Path = temporaryFolder.newFolder().toPath().toRealPath()

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

private class FakePlatform(
  private val gitAvailable: Boolean = true,
  val mappings: MutableList<VcsDirectoryMapping> = mutableListOf(),
  var failSet: Boolean = false,
  var failRefresh: Boolean = false,
  private val onGet: ((Int, MutableList<VcsDirectoryMapping>) -> Unit)? = null,
  private val onSet: (() -> Unit)? = null,
) : VcsMappingPlatform {
  var getCalls = 0
  var setCalls = 0
  var refreshCalls = 0
  val events = mutableListOf<String>()

  override fun isGitAvailable(): Boolean = gitAvailable

  override fun getDirectoryMappings(): List<VcsDirectoryMapping> {
    getCalls += 1
    onGet?.invoke(getCalls, mappings)
    return mappings.toList()
  }

  override fun setDirectoryMappings(mappings: List<VcsDirectoryMapping>) {
    setCalls += 1
    events.add("set")
    if (failSet) throw IllegalStateException("set failed")
    this.mappings.clear()
    this.mappings.addAll(mappings)
    onSet?.invoke()
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
