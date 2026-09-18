package com.reqws.goland.projectmodel

import com.reqws.goland.diagnostics.ReqwsSyncTrace
import com.reqws.goland.diagnostics.SyncTraceEvent
import com.reqws.goland.diagnostics.traceRecords
import com.intellij.openapi.progress.ProcessCanceledException
import kotlinx.coroutines.CancellationException
import com.intellij.openapi.components.service
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.roots.ModuleRootEvent
import com.intellij.openapi.roots.ModuleRootListener
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.platform.backend.workspace.WorkspaceModel
import com.intellij.platform.workspace.jps.entities.ContentRootEntity
import com.intellij.platform.workspace.jps.entities.ExcludeUrlEntity
import com.intellij.platform.workspace.jps.entities.ModuleId
import com.intellij.platform.workspace.jps.entities.modifyContentRootEntity
import com.intellij.platform.workspace.jps.entities.modifyModuleEntity
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.util.concurrency.AppExecutorUtil
import com.reqws.goland.manifest.ManifestReader
import com.reqws.goland.manifest.ManifestSnapshot
import com.reqws.goland.manifest.RepositoryAvailability
import com.reqws.goland.manifest.ResolvedRepository
import com.reqws.goland.manifest.WorkspaceManifest
import com.reqws.goland.manifest.WorkspaceRepository
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.jetbrains.jps.model.serialization.JpsSerializationManager
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.Callable
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class ReqwsProjectModelAdapterTest : BasePlatformTestCase() {
  private var localRoot: Path? = null

  override fun isWriteActionRequired(): Boolean = false

  override fun tearDown() {
    try {
      localRoot?.toFile()?.deleteRecursively()
    } finally {
      super.tearDown()
    }
  }

  fun testAddsRemovesAndReaddsTargetAndPersistentMarkerTogether() {
    val root = rootPath()
    Files.createDirectories(root.resolve(".reqws"))
    gitRepository(root, "repo-a")
    gitRepository(root, "repo-c")
    Files.writeString(root.resolve("repo-c/README.txt"), "retained repository data")
    Files.createDirectories(root.resolve("ordinary"))
    Files.createDirectories(root.resolve("worktree"))
    Files.writeString(root.resolve("worktree/.git"), "gitdir: ../outside")
    addExclude("user-hidden")

    val workspaceModel = WorkspaceModel.getInstance(project)
    val moduleId = ModuleId(module.name)
    val beforeDependencies = requireNotNull(workspaceModel.currentSnapshot.resolve(moduleId)).dependencies
    val state = ReqwsManagedModelState()
    val adapter = WorkspaceExcludeModelAdapter(
      project,
      state,
      isTrusted = { true },
      markerTokenFactory = tokenFactory(TOKEN_A, TOKEN_B, TOKEN_C),
    )
    val rootsChangedCount = AtomicInteger()
    val guardedRootsChangedCount = AtomicInteger()
    val mutationGuard = project.service<ReqwsProjectModelMutationGuard>()
    project.messageBus.connect(testRootDisposable).subscribe(
      ModuleRootListener.TOPIC,
      object : ModuleRootListener {
        override fun rootsChanged(event: ModuleRootEvent) {
          rootsChangedCount.incrementAndGet()
          if (mutationGuard.isActive) guardedRootsChangedCount.incrementAndGet()
        }
      },
    )

    val first = awaitUpdate { adapter.apply(snapshot(root, listOf("repo-a"))) }

    assertEquals(setOf(".reqws", "repo-c"), first.added)
    assertEquals(setOf(".reqws", "repo-c"), first.managedExcludes)
    assertEquals(emptySet<String>(), first.borrowed)
    assertEquals(
      setOf(".reqws", "repo-c", "user-hidden"),
      targetExcludedRelativePaths(root),
    )
    assertEquals(
      setOf(markerRelative(TOKEN_A), markerRelative(TOKEN_B)),
      markerRelativePaths(root),
    )
    assertFalse(targetExcludedRelativePaths(root).contains("ordinary"))
    assertFalse(targetExcludedRelativePaths(root).contains("worktree"))
    assertEquals(beforeDependencies, requireNotNull(workspaceModel.currentSnapshot.resolve(moduleId)).dependencies)
    assertLiveProjection(root, included = setOf("repo-a"), excluded = setOf("repo-c"))
    val rootsChangedAfterInitialProjection = rootsChangedCount.get()
    val guardedRootsChangedAfterInitialProjection = guardedRootsChangedCount.get()
    assertTrue(guardedRootsChangedAfterInitialProjection > 0)

    val second = awaitUpdate { adapter.apply(snapshot(root, listOf("repo-a", "repo-c"))) }

    assertEquals(setOf("repo-c"), second.removed)
    assertEquals(setOf(".reqws"), second.managedExcludes)
    assertEquals(mapOf("repo-c" to setOf(TOKEN_B)), recoveryMap(state))
    assertEquals(setOf(".reqws", "user-hidden"), targetExcludedRelativePaths(root))
    assertEquals(setOf(markerRelative(TOKEN_A)), markerRelativePaths(root))
    assertLiveProjection(root, included = setOf("repo-a", "repo-c"), excluded = emptySet())
    assertTrue(
      "removing a ReqWS exclude must publish a roots-changed event",
      rootsChangedCount.get() > rootsChangedAfterInitialProjection,
    )
    val rootsChangedAfterReactivation = rootsChangedCount.get()
    val guardedRootsChangedAfterReactivation = guardedRootsChangedCount.get()
    assertTrue(
      "removing a ReqWS exclude must keep its roots event inside the mutation guard",
      guardedRootsChangedAfterReactivation > guardedRootsChangedAfterInitialProjection,
    )

    val third = awaitUpdate { adapter.apply(snapshot(root, listOf("repo-a"))) }

    assertEquals(setOf("repo-c"), third.added)
    assertEquals(setOf(".reqws", "repo-c"), third.managedExcludes)
    assertEquals(1, targetExcludedRelativePathsList(root).count { it == "repo-c" })
    assertTrue(targetExcludedRelativePaths(root).contains("user-hidden"))
    assertEquals(
      setOf(markerRelative(TOKEN_A), markerRelative(TOKEN_C)),
      markerRelativePaths(root),
    )
    assertFalse(markerRelativePaths(root).contains(markerRelative(TOKEN_B)))
    assertEquals(beforeDependencies, requireNotNull(workspaceModel.currentSnapshot.resolve(moduleId)).dependencies)
    assertEquals(
      mapOf(".reqws" to TOKEN_A, "repo-c" to TOKEN_C),
      ownershipMap(state),
    )
    assertEquals(mapOf("repo-c" to setOf(TOKEN_B)), recoveryMap(state))
    assertLiveProjection(root, included = setOf("repo-a"), excluded = setOf("repo-c"))
    assertTrue(
      "re-adding a ReqWS exclude must publish a roots-changed event",
      rootsChangedCount.get() > rootsChangedAfterReactivation,
    )
    assertTrue(
      "re-adding a ReqWS exclude must keep its roots event inside the mutation guard",
      guardedRootsChangedCount.get() > guardedRootsChangedAfterReactivation,
    )
    assertEquals("retained repository data", Files.readString(root.resolve("repo-c/README.txt")))

    val readdedRepository = awaitUpdate { adapter.apply(snapshot(root, listOf("repo-a", "repo-c"))) }

    assertEquals(setOf("repo-c"), readdedRepository.removed)
    assertEquals(setOf(".reqws", "user-hidden"), targetExcludedRelativePaths(root))
    assertLiveProjection(root, included = setOf("repo-a", "repo-c", "ordinary"), excluded = setOf(".reqws"))
    assertEquals("retained repository data", Files.readString(root.resolve("repo-c/README.txt")))
    assertEquals(beforeDependencies, requireNotNull(workspaceModel.currentSnapshot.resolve(moduleId)).dependencies)
  }

  fun testVirginProjectionWaitsWithoutSideEffectsThenConvergesAfterIdeaAppears() {
    val root = rootPath()
    Files.delete(root.resolve(".idea"))
    Files.createDirectories(root.resolve(".reqws"))
    val state = ReqwsManagedModelState()
    val adapter = WorkspaceExcludeModelAdapter(
      project,
      state,
      isTrusted = { true },
      markerTokenFactory = tokenFactory(TOKEN_A),
      isColdModelSnapshot = true,
    )
    val before = excludedRelativePathsList(root)

    val initialFailure = expectApplyFailure {
      adapter.apply(snapshot(root, emptyList()))
    }

    assertEquals(ProjectModelErrorCode.PROJECT_METADATA_NOT_READY, initialFailure.code)
    assertEquals(before, excludedRelativePathsList(root))
    assertTrue(state.ownership().managedExcludes.isEmpty())
    assertFalse(Files.exists(root.resolve(".idea")))

    Files.createDirectory(root.resolve(".idea"))
    val result = awaitUpdate { adapter.apply(snapshot(root, emptyList())) }

    assertEquals(setOf(".reqws"), result.added)
    assertEquals(setOf(".reqws"), result.managedExcludes)
    assertEquals(setOf(markerRelative(TOKEN_A)), markerRelativePaths(root))
    assertTrue(
      Files.isRegularFile(root.resolve(".idea").resolve(REQWS_MODEL_STATE_FILE_NAME)),
    )
    assertEquals(0L, requireNotNull(
      VerifiedManagedModelStateRepository(root).read(
        managedModelStateBinding("ws_test", root),
      ),
    ).generation)
  }

  fun testManifestProjectionIsIndependentOfGoModContentsAndLocation() {
    val root = rootPath()
    Files.createDirectories(root.resolve(".reqws"))
    listOf("repo-a", "repo-b", "repo-c").forEach { name ->
      val process = ProcessBuilder("git", "init", "--quiet", "--template=", "--", root.resolve(name).toString())
        .redirectOutput(ProcessBuilder.Redirect.DISCARD)
        .redirectError(ProcessBuilder.Redirect.DISCARD)
        .start()
      try {
        assertTrue("local Git fixture initialization must finish", process.waitFor(10, TimeUnit.SECONDS))
        assertEquals("local Git fixture initialization must succeed", 0, process.exitValue())
      } finally {
        if (process.isAlive) process.destroyForcibly()
      }
      Files.writeString(root.resolve("$name/README.txt"), "ordinary repository content")
    }
    Files.createDirectories(root.resolve("notes"))
    Files.writeString(root.resolve("notes/notes.txt"), "ordinary notes")
    addExclude("user-hidden")
    val adapter = WorkspaceExcludeModelAdapter(project, ReqwsManagedModelState(), isTrusted = { true })
    fun escapeJson(value: String): String = value.replace("\\", "\\\\").replace("\"", "\\\"")
    val repositoriesJson = listOf("repo-a", "repo-b").joinToString(",") { name ->
      """
        {
          "catalogRepositoryId": "$name",
          "name": "$name",
          "url": "https://example.invalid/$name.git",
          "defaultBranch": "main",
          "relativePath": "$name"
        }
      """.trimIndent()
    }
    Files.writeString(
      root.resolve(".reqws/workspace.json"),
      """
        {
          "schemaVersion": 1,
          "id": "ws_test",
          "name": "Text Workspace",
          "featureBranch": "feature/test",
          "rootPath": "${escapeJson(root.toString())}",
          "workspaceFilePath": "${escapeJson(root.resolve("workspace.code-workspace").toString())}",
          "repositories": [$repositoriesJson],
          "createdAt": "2026-08-14T00:00:00.000Z",
          "updatedAt": "2026-08-14T00:00:00.000Z"
        }
      """.trimIndent(),
    )
    val reader = ManifestReader()
    val manifest = reader.read(root)
    val initial = awaitUpdate { adapter.apply(manifest) }
    val initialExcludes = excludedRelativePathsList(root)

    fun assertUnchangedProjection() {
      val reread = reader.read(root)
      assertEquals(manifest.digestSha256, reread.digestSha256)
      assertEquals(listOf("repo-a", "repo-b"), reread.repositories.map { it.repository.name })
      val result = awaitUpdate { adapter.apply(reread) }
      assertEquals(initial.managedExcludes, result.managedExcludes)
      assertTrue(result.added.isEmpty())
      assertTrue(result.removed.isEmpty())
      assertEquals(initialExcludes, excludedRelativePathsList(root))
      assertLiveProjection(
        root,
        included = setOf("repo-a", "repo-b", "notes"),
        excluded = setOf("repo-c", ".reqws"),
      )
      assertEquals("ordinary repository content", Files.readString(root.resolve("repo-c/README.txt")))
      assertEquals("ordinary notes", Files.readString(root.resolve("notes/notes.txt")))
    }

    assertEquals(setOf(".reqws", "repo-c"), initial.managedExcludes)
    assertUnchangedProjection()
    listOf("repo-a", "repo-b", "repo-c").forEach { name ->
      Files.writeString(root.resolve("$name/go.mod"), "not a valid Go module declaration")
    }
    assertUnchangedProjection()
    listOf("repo-a", "repo-b", "repo-c").forEach { name ->
      Files.createDirectories(root.resolve("$name/nested"))
      Files.move(root.resolve("$name/go.mod"), root.resolve("$name/nested/go.mod"))
    }
    assertUnchangedProjection()
  }

  fun testVerifiesLiveProjectionAfterEveryApplyIncludingModelNoOp() {
    val root = rootPath()
    Files.createDirectories(root.resolve(".reqws"))
    gitRepository(root, "repo-a")
    gitRepository(root, "repo-c")
    val calls = mutableListOf<Pair<Set<Path>, Set<Path>>>()
    val verifier = PlatformReqwsLiveProjectionVerifier(project)
    val adapter = WorkspaceExcludeModelAdapter(
      project,
      ReqwsManagedModelState(),
      isTrusted = { true },
      markerTokenFactory = tokenFactory(TOKEN_A, TOKEN_B),
      liveProjectionVerifier = ReqwsLiveProjectionVerifier { active, excluded ->
        verifier.verify(active, excluded)
        calls.add(active.toSet() to excluded.toSet())
      },
    )

    awaitUpdate { adapter.apply(snapshot(root, listOf("repo-a"))) }
    awaitUpdate { adapter.apply(snapshot(root, listOf("repo-a", "repo-c"))) }
    val noOpRootsEvents = AtomicInteger()
    project.messageBus.connect(testRootDisposable).subscribe(
      ModuleRootListener.TOPIC,
      object : ModuleRootListener {
        override fun rootsChanged(event: ModuleRootEvent) {
          noOpRootsEvents.incrementAndGet()
        }
      },
    )
    val noOpModel = awaitUpdate { adapter.apply(snapshot(root, listOf("repo-a", "repo-c"))) }

    assertEquals(0, noOpRootsEvents.get())
    assertTrue(noOpModel.added.isEmpty())
    assertTrue(noOpModel.removed.isEmpty())
    assertEquals(3, calls.size)
    assertEquals(setOf(root.resolve("repo-a"), root.resolve("repo-c")), calls.last().first)
    assertEquals(setOf(root.resolve(".reqws")), calls.last().second)
  }

  fun testPropagatesLiveFileIndexFailureAndRecoversWithTheSameSnapshot() {
    val root = rootPath()
    Files.createDirectories(root.resolve(".reqws"))
    gitRepository(root, "repo-a")
    val liveFailure = ProjectModelApplyException(
      ProjectModelErrorCode.LIVE_FILE_INDEX_NOT_CONVERGED,
      "live projection failed",
    )
    var shouldFail = true
    val verifier = PlatformReqwsLiveProjectionVerifier(project)
    val adapter = WorkspaceExcludeModelAdapter(
      project,
      ReqwsManagedModelState(),
      isTrusted = { true },
      markerTokenFactory = tokenFactory(TOKEN_A),
      liveProjectionVerifier = ReqwsLiveProjectionVerifier { active, excluded ->
        if (shouldFail) throw liveFailure
        verifier.verify(active, excluded)
      },
    )
    val manifest = snapshot(root, listOf("repo-a"))
    val thrown = expectApplyFailure { adapter.apply(manifest) }

    assertSame(liveFailure, thrown)
    shouldFail = false
    val recovered = awaitUpdate { adapter.apply(manifest) }
    assertEquals(setOf(".reqws"), recovered.managedExcludes)
    assertTrue(recovered.added.isEmpty())
    assertTrue(recovered.removed.isEmpty())
    assertLiveProjection(root, included = setOf("repo-a"), excluded = setOf(".reqws"))
  }

  fun testRejectsTrustRevocationAfterLiveFileIndexConverges() {
    assertFinalLifecycleGate(dispose = false)
  }

  fun testRejectsServiceDisposalAfterLiveFileIndexConverges() {
    assertFinalLifecycleGate(dispose = true)
  }

  private fun assertFinalLifecycleGate(dispose: Boolean) {
    val root = rootPath()
    Files.createDirectories(root.resolve(".reqws"))
    gitRepository(root, "repo-a")
    var verified = false
    val verifier = PlatformReqwsLiveProjectionVerifier(project)
    val adapter = WorkspaceExcludeModelAdapter(
      project,
      ReqwsManagedModelState(),
      isTrusted = { dispose || !verified },
      isProjectDisposed = { dispose && verified },
      liveProjectionVerifier = ReqwsLiveProjectionVerifier { active, excluded ->
        verifier.verify(active, excluded)
        verified = true
      },
    )

    val thrown = expectApplyFailure { adapter.apply(snapshot(root, listOf("repo-a"))) }

    assertTrue(verified)
    assertEquals(
      if (dispose) ProjectModelErrorCode.PROJECT_DISPOSED else ProjectModelErrorCode.UNTRUSTED_PROJECT,
      thrown.code,
    )
  }

  fun testRemovesOwnedTargetAfterStateReloadWithoutRuntimeEntityTags() {
    val root = rootPath()
    Files.createDirectories(root.resolve(".reqws"))
    gitRepository(root, "repo-a")
    gitRepository(root, "repo-c")
    addExclude("user-hidden")
    val originalState = ReqwsManagedModelState()
    val firstAdapter = WorkspaceExcludeModelAdapter(
      project,
      originalState,
      isTrusted = { true },
      markerTokenFactory = tokenFactory(TOKEN_A, TOKEN_B),
    )
    awaitUpdate { firstAdapter.apply(snapshot(root, listOf("repo-a"))) }
    val reloadedState = ReqwsManagedModelState().also { it.loadState(originalState.state) }

    val result = awaitUpdate {
      WorkspaceExcludeModelAdapter(
        project,
        reloadedState,
        isTrusted = { true },
        markerTokenFactory = tokenFactory(TOKEN_C),
      ).apply(snapshot(root, listOf("repo-a", "repo-c")))
    }

    assertEquals(setOf("repo-c"), result.removed)
    assertFalse(targetExcludedRelativePaths(root).contains("repo-c"))
    assertFalse(markerRelativePaths(root).contains(markerRelative(TOKEN_B)))
    assertTrue(targetExcludedRelativePaths(root).contains("user-hidden"))
    assertEquals(mapOf(".reqws" to TOKEN_A), ownershipMap(reloadedState))
  }

  fun testLoadsTargetsAndMarkersThroughTheIndependentJpsSerializationContract() {
    val root = rootPath()
    Files.createDirectories(root.resolve(".reqws"))
    gitRepository(root, "repo-c")
    addExclude("user-hidden")
    val state = ReqwsManagedModelState()
    val adapter = WorkspaceExcludeModelAdapter(
      project,
      state,
      isTrusted = { true },
      markerTokenFactory = tokenFactory(TOKEN_A, TOKEN_B),
    )
    awaitUpdate { adapter.apply(snapshot(root, emptyList())) }
    val liveExcludeUrls = excludedUrls().toSet()

    // BasePlatformTestCase uses an in-memory project and does not materialize its module file.
    // Generate the minimal public JPS serialization format independently so this test locks the
    // loader contract for ordinary target excludes and nonexistent nested marker URLs without
    // pretending to exercise an IDE close/reopen lifecycle.
    val serializedRoot = root.resolve("jps-contract")
    val ideaDirectory = serializedRoot.resolve(".idea")
    Files.createDirectories(ideaDirectory)
    val serializedModuleName = "reqws-jps-contract"
    val moduleFile = serializedRoot.resolve("$serializedModuleName.iml")
    fun xmlAttribute(value: String): String = value
      .replace("&", "&amp;")
      .replace("\"", "&quot;")
      .replace("<", "&lt;")
      .replace(">", "&gt;")
    val excludeElements = liveExcludeUrls.joinToString(separator = "\n") { url ->
      "      <excludeFolder url=\"${xmlAttribute(url)}\" />"
    }
    Files.writeString(
      moduleFile,
      """<?xml version="1.0" encoding="UTF-8"?>
<module type="JAVA_MODULE" version="4">
  <component name="NewModuleRootManager" inherit-compiler-output="true">
    <exclude-output />
    <content url="${xmlAttribute(root.toUri().toString().removeSuffix("/"))}">
$excludeElements
    </content>
    <orderEntry type="inheritedJdk" />
    <orderEntry type="sourceFolder" forTests="false" />
  </component>
</module>
""",
    )
    Files.writeString(
      ideaDirectory.resolve("modules.xml"),
      """<?xml version="1.0" encoding="UTF-8"?>
<project version="4">
  <component name="ProjectModuleManager">
    <modules>
      <module fileurl="${xmlAttribute(moduleFile.toUri().toString())}" filepath="${xmlAttribute(moduleFile.toString())}" />
    </modules>
  </component>
</project>
""",
    )
    val serializedProject = JpsSerializationManager.getInstance().loadProject(
      serializedRoot.toString(),
      emptyMap<String, String>(),
    )
    val serializedExcludeUrls = requireNotNull(
      serializedProject.findModuleByName(serializedModuleName),
    ).excludeRootsList.urls.toSet()

    assertEquals(setOf(".reqws", "repo-c", "user-hidden"), targetExcludedRelativePaths(root))
    assertEquals(
      setOf(markerRelative(TOKEN_A), markerRelative(TOKEN_B)),
      markerRelativePaths(root),
    )
    assertTrue(serializedExcludeUrls.containsAll(liveExcludeUrls))
  }

  fun testRejectsMissingOwnershipMarkerWithoutChangingModelOrState() {
    val root = rootPath()
    Files.createDirectories(root.resolve(".reqws"))
    val state = ReqwsManagedModelState()
    val adapter = WorkspaceExcludeModelAdapter(
      project,
      state,
      isTrusted = { true },
      markerTokenFactory = tokenFactory(TOKEN_A),
    )
    awaitUpdate { adapter.apply(snapshot(root, emptyList())) }
    removeExclude(markerRelative(TOKEN_A))
    val beforeModel = excludedRelativePathsList(root)
    val beforeState = ownershipMap(state)

    val failure = expectApplyFailure { adapter.apply(snapshot(root, emptyList())) }

    assertEquals(ProjectModelErrorCode.OWNERSHIP_CONFLICT, failure.code)
    assertEquals(beforeModel, excludedRelativePathsList(root))
    assertEquals(beforeState, ownershipMap(state))
  }

  fun testRejectsMissingOwnedTargetWithoutChangingMarkerOrState() {
    val root = rootPath()
    Files.createDirectories(root.resolve(".reqws"))
    val state = ReqwsManagedModelState()
    val adapter = WorkspaceExcludeModelAdapter(
      project,
      state,
      isTrusted = { true },
      markerTokenFactory = tokenFactory(TOKEN_A),
    )
    awaitUpdate { adapter.apply(snapshot(root, emptyList())) }
    removeExclude(".reqws")
    val beforeModel = excludedRelativePathsList(root)
    val beforeState = ownershipMap(state)

    val failure = expectApplyFailure { adapter.apply(snapshot(root, emptyList())) }

    assertEquals(ProjectModelErrorCode.OWNERSHIP_CONFLICT, failure.code)
    assertEquals(beforeModel, excludedRelativePathsList(root))
    assertEquals(beforeState, ownershipMap(state))
  }

  fun testRejectsPartialPendingAddWithoutDeletingTheUserVisibleTarget() {
    val root = rootPath()
    Files.createDirectories(root.resolve(".reqws"))
    addExclude(".reqws")
    val state = ReqwsManagedModelState().also { service ->
      service.replaceOwnership(
        moduleName = module.name,
        managedExcludes = emptyMap(),
        pendingAdds = mapOf(".reqws" to TOKEN_A),
      )
    }
    val beforeModel = excludedRelativePathsList(root)

    val failure = expectApplyFailure {
      WorkspaceExcludeModelAdapter(
        project,
        state,
        isTrusted = { true },
      ).apply(snapshot(root, emptyList()))
    }

    assertEquals(ProjectModelErrorCode.OWNERSHIP_CONFLICT, failure.code)
    assertEquals(beforeModel, excludedRelativePathsList(root))
    assertTrue(targetExcludedRelativePaths(root).contains(".reqws"))
    assertEquals(mapOf(".reqws" to TOKEN_A), pendingAddMap(state))
    assertTrue(ownershipMap(state).isEmpty())
  }

  fun testRestartsAnAbsentPendingRemovalWithAFreshMarkerToken() {
    val root = rootPath()
    Files.createDirectories(root.resolve(".reqws"))
    gitRepository(root, "repo-c")
    addExclude(".reqws")
    addExclude(markerRelative(TOKEN_A))
    val state = ReqwsManagedModelState().also { service ->
      service.replaceOwnership(
        moduleName = module.name,
        managedExcludes = mapOf(".reqws" to TOKEN_A),
        pendingRemovals = mapOf("repo-c" to TOKEN_B),
      )
    }

    val result = awaitUpdate {
      WorkspaceExcludeModelAdapter(
        project,
        state,
        isTrusted = { true },
        markerTokenFactory = tokenFactory(TOKEN_C),
        afterDurableStatePersisted = {},
      ).apply(snapshot(root, emptyList()))
    }

    assertEquals(setOf("repo-c"), result.added)
    assertEquals(
      mapOf(".reqws" to TOKEN_A, "repo-c" to TOKEN_C),
      ownershipMap(state),
    )
    assertTrue(pendingRemoveMap(state).isEmpty())
    assertFalse(markerRelativePaths(root).contains(markerRelative(TOKEN_B)))
    assertTrue(markerRelativePaths(root).contains(markerRelative(TOKEN_C)))
  }

  fun testRejectsDuplicateOwnershipMarkerWithoutChangingModelOrState() {
    val root = rootPath()
    Files.createDirectories(root.resolve(".reqws"))
    val state = ReqwsManagedModelState()
    val adapter = WorkspaceExcludeModelAdapter(
      project,
      state,
      isTrusted = { true },
      markerTokenFactory = tokenFactory(TOKEN_A),
    )
    awaitUpdate { adapter.apply(snapshot(root, emptyList())) }
    addExclude(markerRelative(TOKEN_A))
    val beforeModel = excludedRelativePathsList(root)
    val beforeState = ownershipMap(state)

    val failure = expectApplyFailure { adapter.apply(snapshot(root, emptyList())) }

    assertEquals(ProjectModelErrorCode.OWNERSHIP_CONFLICT, failure.code)
    assertEquals(beforeModel, excludedRelativePathsList(root))
    assertEquals(beforeState, ownershipMap(state))
  }

  fun testBorrowsExistingTargetWithoutCreatingDeletionProof() {
    val root = rootPath()
    Files.createDirectories(root.resolve(".reqws"))
    gitRepository(root, "repo-c")
    addExclude("repo-c")
    val state = ReqwsManagedModelState()

    val result = awaitUpdate {
      WorkspaceExcludeModelAdapter(
        project,
        state,
        isTrusted = { true },
        markerTokenFactory = tokenFactory(TOKEN_A, TOKEN_B),
      ).apply(snapshot(root, emptyList()))
    }

    assertTrue(result.borrowed.contains("repo-c"))
    assertFalse(ownershipMap(state).containsKey("repo-c"))
    assertEquals(1, targetExcludedRelativePathsList(root).count { it == "repo-c" })
  }

  fun testAddsAnOwnedExactExcludeWhenAnExistingFilesystemAliasDoesNotAffectTheLiveIndex() {
    val root = rootPath()
    Files.createDirectories(root.resolve(".reqws"))
    gitRepository(root, "repo-c")
    Files.createSymbolicLink(root.resolve("retained-alias"), root.resolve("repo-c"))
    addExclude("retained-alias")
    val state = ReqwsManagedModelState()

    val result = awaitUpdate {
      WorkspaceExcludeModelAdapter(
        project,
        state,
        isTrusted = { true },
        markerTokenFactory = tokenFactory(TOKEN_A, TOKEN_B),
      ).apply(snapshot(root, emptyList()))
    }

    assertTrue(result.added.contains("repo-c"))
    assertFalse(result.borrowed.contains("repo-c"))
    assertEquals(TOKEN_B, ownershipMap(state)["repo-c"])
    assertTrue(targetExcludedRelativePaths(root).contains("retained-alias"))
    assertTrue(targetExcludedRelativePaths(root).contains("repo-c"))
    assertEquals(2, markerRelativePaths(root).size)
    assertLiveProjection(root, included = emptySet(), excluded = setOf("repo-c"))
  }

  fun testUsesFilesystemIdentityWhenManifestCaseDiffersFromAnActiveRepository() {
    val root = rootPath()
    Files.createDirectories(root.resolve(".reqws"))
    gitRepository(root, "repo-a")

    val result = awaitUpdate {
      WorkspaceExcludeModelAdapter(
        project,
        ReqwsManagedModelState(),
        isTrusted = { true },
        markerTokenFactory = tokenFactory(TOKEN_A),
      ).apply(snapshot(root, listOf("Repo-A")))
    }

    assertFalse(result.managedExcludes.contains("repo-a"))
    assertFalse(targetExcludedRelativePaths(root).contains("repo-a"))
  }

  fun testDoesNotExcludeAnActiveRepositoryThatAppearsAfterManifestRead() {
    val root = rootPath()
    Files.createDirectories(root.resolve(".reqws"))
    val beforeRepositoryAppears = snapshot(root, listOf("repo-a"))
    gitRepository(root, "repo-a")

    val result = awaitUpdate {
      WorkspaceExcludeModelAdapter(
        project,
        ReqwsManagedModelState(),
        isTrusted = { true },
        markerTokenFactory = tokenFactory(TOKEN_A),
      ).apply(beforeRepositoryAppears)
    }

    assertFalse(result.managedExcludes.contains("repo-a"))
    assertFalse(targetExcludedRelativePaths(root).contains("repo-a"))
  }

  fun testPreservesUnrelatedDuplicateUserExcludes() {
    val root = rootPath()
    Files.createDirectories(root.resolve(".reqws"))
    addExclude("user-hidden")
    addExclude("user-hidden")

    awaitUpdate {
      WorkspaceExcludeModelAdapter(
        project,
        ReqwsManagedModelState(),
        isTrusted = { true },
        markerTokenFactory = tokenFactory(TOKEN_A),
      ).apply(snapshot(root, emptyList()))
    }

    assertEquals(2, targetExcludedRelativePathsList(root).count { it == "user-hidden" })
  }

  fun testRejectsANestedContentRootReachedThroughASymlinkAlias() {
    val root = rootPath()
    Files.createDirectories(root.resolve(".reqws"))
    gitRepository(root, "repo-c")
    Files.createSymbolicLink(root.resolve("repo-alias"), root.resolve("repo-c"))
    addContentRoot(root.resolve("repo-alias"))
    val state = ReqwsManagedModelState()
    val before = excludedRelativePathsList(root)

    val failure = expectApplyFailure {
      WorkspaceExcludeModelAdapter(
        project,
        state,
        isTrusted = { true },
        markerTokenFactory = tokenFactory(TOKEN_A, TOKEN_B),
      ).apply(snapshot(root, emptyList()))
    }

    assertEquals(ProjectModelErrorCode.NESTED_CONTENT_ROOT_CONFLICT, failure.code)
    assertEquals(before, excludedRelativePathsList(root))
    assertTrue(state.ownership().managedExcludes.isEmpty())
  }

  fun testRejectsNestedContentRootEvenWhenARetainedNameMatchesAMarkerKeyPrefix() {
    val root = rootPath()
    Files.createDirectories(root.resolve(".reqws"))
    gitRepository(root, "candidate:.reqws")
    addContentRoot(root.resolve("candidate:.reqws"))
    val state = ReqwsManagedModelState()
    val before = excludedRelativePathsList(root)

    val failure = expectApplyFailure {
      WorkspaceExcludeModelAdapter(
        project,
        state,
        isTrusted = { true },
        markerTokenFactory = tokenFactory(TOKEN_A, TOKEN_B),
      ).apply(snapshot(root, emptyList()))
    }

    assertEquals(ProjectModelErrorCode.NESTED_CONTENT_ROOT_CONFLICT, failure.code)
    assertEquals(before, excludedRelativePathsList(root))
    assertTrue(state.ownership().managedExcludes.isEmpty())
  }

  fun testRejectsAnActiveRepositoryExcludedByABorrowedEntry() {
    val root = rootPath()
    Files.createDirectories(root.resolve(".reqws"))
    gitRepository(root, "repo-a")
    addExclude("repo-a")
    val state = ReqwsManagedModelState()
    val before = excludedRelativePathsList(root)

    val failure = expectApplyFailure {
      WorkspaceExcludeModelAdapter(
        project,
        state,
        isTrusted = { true },
        markerTokenFactory = tokenFactory(TOKEN_A),
      ).apply(snapshot(root, listOf("repo-a")))
    }

    assertEquals(ProjectModelErrorCode.OWNERSHIP_CONFLICT, failure.code)
    assertEquals(before, excludedRelativePathsList(root))
    assertTrue(state.ownership().managedExcludes.isEmpty())
  }

  fun testPreservesFilesystemAliasWithoutTreatingTheActiveRepositoryAsExcluded() {
    val root = rootPath()
    Files.createDirectories(root.resolve(".reqws"))
    gitRepository(root, "repo-a")
    Files.createSymbolicLink(root.resolve("active-alias"), root.resolve("repo-a"))
    addExclude("active-alias")
    val state = ReqwsManagedModelState()

    val result = awaitUpdate {
      WorkspaceExcludeModelAdapter(
        project,
        state,
        isTrusted = { true },
        markerTokenFactory = tokenFactory(TOKEN_A),
      ).apply(snapshot(root, listOf("repo-a")))
    }

    assertEquals(setOf(".reqws"), result.managedExcludes)
    assertTrue(targetExcludedRelativePaths(root).contains("active-alias"))
    assertFalse(targetExcludedRelativePaths(root).contains("repo-a"))
    assertLiveProjection(root, included = setOf("repo-a"), excluded = emptySet())
  }

  fun testFailsClosedWhenAnExistingFilesystemIdentityCannotBeCompared() {
    val root = rootPath()
    Files.createDirectories(root.resolve(".reqws"))
    gitRepository(root, "repo-c")
    Files.createSymbolicLink(root.resolve("retained-alias"), root.resolve("repo-c"))
    addExclude("retained-alias")
    val state = ReqwsManagedModelState()
    val before = excludedRelativePathsList(root)

    val failure = expectApplyFailure {
      WorkspaceExcludeModelAdapter(
        project,
        state,
        isTrusted = { true },
        markerTokenFactory = tokenFactory(TOKEN_A, TOKEN_B),
        pathsReferToSameFile = { _, _ -> throw IOException("injected identity failure") },
      ).apply(snapshot(root, emptyList()))
    }

    assertEquals(ProjectModelErrorCode.OWNERSHIP_CONFLICT, failure.code)
    assertEquals(before, excludedRelativePathsList(root))
    assertTrue(state.ownership().managedExcludes.isEmpty())
  }

  fun testRejectsAPhysicalOrSymlinkedMarkerNamespace() {
    val root = rootPath()
    Files.createDirectories(root.resolve(".reqws"))
    Files.createSymbolicLink(root.resolve(".reqws/.goland-ownership"), root)
    val state = ReqwsManagedModelState()
    val before = excludedRelativePathsList(root)

    val failure = expectApplyFailure {
      WorkspaceExcludeModelAdapter(
        project,
        state,
        isTrusted = { true },
        markerTokenFactory = tokenFactory(TOKEN_A),
      ).apply(snapshot(root, emptyList()))
    }

    assertEquals(ProjectModelErrorCode.OWNERSHIP_CONFLICT, failure.code)
    assertEquals(before, excludedRelativePathsList(root))
    assertTrue(state.ownership().managedExcludes.isEmpty())
  }

  fun testRejectsLegacyOwnershipStateWithoutAuthorizingAnyModelChange() {
    val root = rootPath()
    val state = ReqwsManagedModelState().also { service ->
      service.loadState(ReqwsManagedModelState.Data().also { data ->
        data.stateVersion = 1
      })
    }
    val before = excludedRelativePathsList(root)

    val failure = expectApplyFailure {
      WorkspaceExcludeModelAdapter(
        project,
        state,
        isTrusted = { true },
        markerTokenFactory = tokenFactory(TOKEN_A),
      ).apply(snapshot(root, emptyList()))
    }

    assertEquals(ProjectModelErrorCode.INVALID_OWNERSHIP_STATE, failure.code)
    assertEquals(before, excludedRelativePathsList(root))
    assertEquals(1, state.ownership().stateVersion)
  }

  fun testMigratesVersionTwoOwnershipStateOnTheNextSuccessfulApply() {
    val root = rootPath()
    Files.createDirectories(root.resolve(".reqws"))
    addExclude(".reqws")
    addExclude(markerRelative(TOKEN_A))
    val state = ReqwsManagedModelState().also { service ->
      service.loadState(ReqwsManagedModelState.Data().also { data ->
        data.stateVersion = REQWS_LEGACY_MODEL_STATE_VERSION
        data.targetModuleName = module.name
        data.managedExcludes = mutableListOf(persistedClaim(".reqws", TOKEN_A))
      })
    }

    val migrated = awaitUpdate {
      WorkspaceExcludeModelAdapter(
        project,
        state,
        isTrusted = { true },
      ).apply(snapshot(root, emptyList()))
    }

    assertEquals(setOf(".reqws"), migrated.kept)
    assertEquals(REQWS_MODEL_STATE_VERSION, state.ownership().stateVersion)
    assertEquals(mapOf(".reqws" to TOKEN_A), ownershipMap(state))
  }

  fun testRejectsMalformedAndDuplicateOwnershipClaims() {
    val root = rootPath()
    val malformedStates = listOf(
      module.name to emptyList(),
      "" to listOf(persistedClaim(".reqws", TOKEN_A)),
      module.name to listOf(persistedClaim(".reqws", "not-a-valid-token")),
      module.name to listOf(
        persistedClaim(".reqws", TOKEN_A),
        persistedClaim(".reqws", TOKEN_B),
      ),
      module.name to listOf(
        persistedClaim(".reqws", TOKEN_A),
        persistedClaim("repo-c", TOKEN_A),
      ),
    )

    malformedStates.forEach { (targetModuleName, claims) ->
      val state = ReqwsManagedModelState().also { service ->
        service.loadState(ReqwsManagedModelState.Data().also { data ->
          data.targetModuleName = targetModuleName
          data.managedExcludes = claims.toMutableList()
        })
      }
      val before = excludedRelativePathsList(root)

      val failure = expectApplyFailure {
        WorkspaceExcludeModelAdapter(
          project,
          state,
          isTrusted = { true },
          markerTokenFactory = tokenFactory(TOKEN_C),
        ).apply(snapshot(root, emptyList()))
      }

      assertEquals(ProjectModelErrorCode.INVALID_OWNERSHIP_STATE, failure.code)
      assertEquals(before, excludedRelativePathsList(root))
      assertEquals(claims.size, state.ownership().managedExcludes.size)
    }
  }

  fun testRejectsClaimsDuplicatedAcrossStableAndPendingPhases() {
    val root = rootPath()
    val state = ReqwsManagedModelState().also { service ->
      service.replaceOwnership(
        moduleName = module.name,
        managedExcludes = mapOf(".reqws" to TOKEN_A),
        pendingAdds = mapOf(".reqws" to TOKEN_B),
      )
    }
    val before = excludedRelativePathsList(root)

    val failure = expectApplyFailure {
      WorkspaceExcludeModelAdapter(
        project,
        state,
        isTrusted = { true },
      ).apply(snapshot(root, emptyList()))
    }

    assertEquals(ProjectModelErrorCode.INVALID_OWNERSHIP_STATE, failure.code)
    assertEquals(before, excludedRelativePathsList(root))
    assertEquals(mapOf(".reqws" to TOKEN_A), ownershipMap(state))
    assertEquals(mapOf(".reqws" to TOKEN_B), pendingAddMap(state))
  }

  fun testRejectsUntrustedProjectBeforeChangingModelOrOwnership() {
    val root = rootPath()
    val state = ReqwsManagedModelState()
    val before = excludedRelativePathsList(root)

    val failure = expectApplyFailure {
      WorkspaceExcludeModelAdapter(
        project,
        state,
        isTrusted = { false },
        markerTokenFactory = tokenFactory(TOKEN_A),
      ).apply(snapshot(root, emptyList()))
    }

    assertEquals(ProjectModelErrorCode.UNTRUSTED_PROJECT, failure.code)
    assertEquals(before, excludedRelativePathsList(root))
    assertTrue(state.ownership().managedExcludes.isEmpty())
  }

  fun testDoesNotMutateModelOrMirrorWhenDurableStateWriteFails() {
    val root = rootPath()
    Files.createDirectories(root.resolve(".reqws"))
    val state = ReqwsManagedModelState()
    val before = excludedRelativePathsList(root)
    var writeAttempts = 0
    val failingRepository = object : ManagedModelStateRepository {
      override fun read(binding: ManagedModelStateBinding): DurableManagedModelState? = null

      override fun write(
        binding: ManagedModelStateBinding,
        expectedGeneration: Long?,
        nextState: DurableManagedModelState,
      ): DurableManagedModelState {
        writeAttempts++
        throw ProjectModelApplyException(
          ProjectModelErrorCode.INVALID_OWNERSHIP_STATE,
          "injected durable persistence failure",
        )
      }
    }

    val failure = expectApplyFailure {
      WorkspaceExcludeModelAdapter(
        project,
        state,
        isTrusted = { true },
        markerTokenFactory = tokenFactory(TOKEN_A),
        stateRepositoryFactory = { failingRepository },
      ).apply(snapshot(root, emptyList()))
    }

    assertEquals(ProjectModelErrorCode.INVALID_OWNERSHIP_STATE, failure.code)
    assertEquals(1, writeAttempts)
    assertEquals(before, excludedRelativePathsList(root))
    assertTrue(state.ownership().managedExcludes.isEmpty())
    assertTrue(state.ownership().recoveryClaims.isEmpty())
  }

  fun testOnlyPristineColdProjectionCanWaitForProjectMetadata() {
    val root = rootPath()
    val state = ReqwsManagedModelState()
    val observation = ProjectMetadataObservation()
    val unavailableRepository = object : ManagedModelStateRepository {
      override fun read(binding: ManagedModelStateBinding): DurableManagedModelState? {
        throw ProjectModelApplyException(
          ProjectModelErrorCode.PROJECT_METADATA_NOT_READY,
          "injected virgin project metadata gap",
        )
      }

      override fun write(
        binding: ManagedModelStateBinding,
        expectedGeneration: Long?,
        nextState: DurableManagedModelState,
      ): DurableManagedModelState = error("write must not be reached")
    }

    val initialFailure = expectApplyFailure {
      WorkspaceExcludeModelAdapter(
        project,
        state,
        isTrusted = { true },
        projectMetadataObservation = observation,
        stateRepositoryFactory = { unavailableRepository },
        isColdModelSnapshot = true,
      ).apply(snapshot(root, emptyList()))
    }

    assertEquals(ProjectModelErrorCode.PROJECT_METADATA_NOT_READY, initialFailure.code)
    assertTrue(state.ownership().managedExcludes.isEmpty())

    observation.markRealDirectoryObserved()
    val observedFailure = expectApplyFailure {
      WorkspaceExcludeModelAdapter(
        project,
        state,
        isTrusted = { true },
        projectMetadataObservation = observation,
        stateRepositoryFactory = { unavailableRepository },
        isColdModelSnapshot = true,
      ).apply(snapshot(root, emptyList()))
    }

    assertEquals(ProjectModelErrorCode.INVALID_OWNERSHIP_STATE, observedFailure.code)

    val historicalState = ReqwsManagedModelState().also { mirror ->
      mirror.replaceOwnership(module.name, emptyMap())
    }
    val historicalFailure = expectApplyFailure {
      WorkspaceExcludeModelAdapter(
        project,
        historicalState,
        isTrusted = { true },
        stateRepositoryFactory = { unavailableRepository },
        isColdModelSnapshot = true,
      ).apply(snapshot(root, emptyList()))
    }

    assertEquals(ProjectModelErrorCode.INVALID_OWNERSHIP_STATE, historicalFailure.code)
  }

  fun testRejectsForeignJvmWriterDuringAHotSession() {
    val root = rootPath()
    val binding = managedModelStateBinding("ws_test", root)
    val repository = VerifiedManagedModelStateRepository(root)
    repository.write(
      binding = binding,
      expectedGeneration = null,
      nextState = durableState(root, writerJvmEpoch = EPOCH_A),
    )
    val mirror = ReqwsManagedModelState()

    val failure = expectApplyFailure {
      WorkspaceExcludeModelAdapter(
        project,
        mirror,
        isTrusted = { true },
        jvmEpoch = EPOCH_B,
        isColdModelSnapshot = false,
      ).apply(snapshot(root, emptyList()))
    }

    assertEquals(ProjectModelErrorCode.INVALID_OWNERSHIP_STATE, failure.code)
    assertTrue(mirror.ownership().managedExcludes.isEmpty())
    assertEquals(EPOCH_A, requireNotNull(repository.read(binding)).writerJvmEpoch)
  }

  fun testNewJvmColdSnapshotCompactsOnlyAbsentRecoveryPairs() {
    val root = rootPath()
    Files.createDirectories(root.resolve(".reqws"))
    val binding = managedModelStateBinding("ws_test", root)
    val repository = VerifiedManagedModelStateRepository(root)
    repository.write(
      binding = binding,
      expectedGeneration = null,
      nextState = durableState(
        root = root,
        writerJvmEpoch = EPOCH_A,
        targetModuleName = module.name,
        recoveryClaims = listOf(DurableManagedClaim("repo-gone", TOKEN_A)),
      ),
    )
    val mirror = ReqwsManagedModelState()

    val result = awaitUpdate {
      WorkspaceExcludeModelAdapter(
        project,
        mirror,
        isTrusted = { true },
        markerTokenFactory = tokenFactory(TOKEN_B),
        jvmEpoch = EPOCH_B,
        isColdModelSnapshot = true,
      ).apply(snapshot(root, emptyList()))
    }

    assertEquals(setOf(".reqws"), result.added)
    assertEquals(mapOf(".reqws" to TOKEN_B), ownershipMap(mirror))
    assertTrue(mirror.ownership().recoveryClaims.isEmpty())
    val persisted = requireNotNull(repository.read(binding))
    assertEquals(EPOCH_B, persisted.writerJvmEpoch)
    assertTrue(persisted.recoveryClaims.isEmpty())
  }

  fun testDoesNotMutateModelWhenTrustChangesAfterDurableIntent() {
    val root = rootPath()
    Files.createDirectories(root.resolve(".reqws"))
    val state = ReqwsManagedModelState()
    val before = excludedRelativePathsList(root)
    var trusted = true

    val failure = expectApplyFailure {
      WorkspaceExcludeModelAdapter(
        project,
        state,
        isTrusted = { trusted },
        markerTokenFactory = tokenFactory(TOKEN_A),
        afterDurableStatePersisted = { trusted = false },
      ).apply(snapshot(root, emptyList()))
    }

    assertEquals(ProjectModelErrorCode.UNTRUSTED_PROJECT, failure.code)
    assertEquals(before, excludedRelativePathsList(root))
    assertEquals(mapOf(".reqws" to TOKEN_A), ownershipMap(state))
    assertTrue(state.ownership().pendingAdds.isEmpty())
  }

  fun testPersistsFinalIntentBeforeTheModelCommit() {
    val root = rootPath()
    Files.createDirectories(root.resolve(".reqws"))
    val state = ReqwsManagedModelState()
    val durableStates = mutableListOf<ReqwsManagedModelState.Data>()
    val durableModels = mutableListOf<Set<String>>()

    awaitUpdate {
      WorkspaceExcludeModelAdapter(
        project,
        state,
        isTrusted = { true },
        markerTokenFactory = tokenFactory(TOKEN_A),
        afterDurableStatePersisted = {
          durableStates.add(state.state)
          durableModels.add(excludedRelativePathsList(root).toSet())
        },
      ).apply(snapshot(root, emptyList()))
    }

    assertEquals(1, durableStates.size)
    assertEquals(mapOf(".reqws" to TOKEN_A), ownershipMap(durableStates.single()))
    assertTrue(pendingAddMap(durableStates.single()).isEmpty())
    assertFalse(durableModels[0].contains(".reqws"))

    val coldState = ReqwsManagedModelState().also { it.loadState(durableStates.single()) }
    val recovered = awaitUpdate {
      WorkspaceExcludeModelAdapter(
        project,
        coldState,
        isTrusted = { true },
        afterDurableStatePersisted = {},
      ).apply(snapshot(root, emptyList()))
    }

    assertEquals(setOf(".reqws"), recovered.kept)
    assertEquals(mapOf(".reqws" to TOKEN_A), ownershipMap(coldState))
    assertTrue(pendingAddMap(coldState).isEmpty())
    assertEquals(mapOf(".reqws" to TOKEN_A), ownershipMap(state))
    assertTrue(pendingAddMap(state).isEmpty())
  }

  fun testFailsClosedWhenExcludesChangeAfterTheFinalIntentIsPersisted() {
    val root = rootPath()
    Files.createDirectories(root.resolve(".reqws"))
    val state = ReqwsManagedModelState()
    var durableState: ReqwsManagedModelState.Data? = null

    val failure = expectApplyFailure {
      WorkspaceExcludeModelAdapter(
        project,
        state,
        isTrusted = { true },
        markerTokenFactory = tokenFactory(TOKEN_A),
        afterDurableStatePersisted = {
          durableState = state.state
          addExclude("user-after-plan")
        },
      ).apply(snapshot(root, emptyList()))
    }

    assertEquals(ProjectModelErrorCode.OWNERSHIP_CONFLICT, failure.code)
    assertEquals(setOf("user-after-plan"), targetExcludedRelativePaths(root))
    assertEquals(mapOf(".reqws" to TOKEN_A), ownershipMap(state))
    val coldState = ReqwsManagedModelState().also {
      it.loadState(requireNotNull(durableState))
    }
    assertEquals(mapOf(".reqws" to TOKEN_A), ownershipMap(coldState))
  }

  fun testDoesNotMutateModelWhenProjectIsDisposedAfterDurableIntent() {
    val root = rootPath()
    Files.createDirectories(root.resolve(".reqws"))
    val state = ReqwsManagedModelState()
    val before = excludedRelativePathsList(root)
    var disposed = false

    val failure = expectApplyFailure {
      WorkspaceExcludeModelAdapter(
        project,
        state,
        isTrusted = { true },
        isProjectDisposed = { disposed },
        markerTokenFactory = tokenFactory(TOKEN_A),
        afterDurableStatePersisted = { disposed = true },
      ).apply(snapshot(root, emptyList()))
    }

    assertEquals(ProjectModelErrorCode.PROJECT_DISPOSED, failure.code)
    assertEquals(before, excludedRelativePathsList(root))
    assertEquals(mapOf(".reqws" to TOKEN_A), ownershipMap(state))
    assertTrue(state.ownership().pendingAdds.isEmpty())
  }

  fun testPersistsRecoveryBeforeRemovalAndRetriesFromDurableIntent() {
    val root = rootPath()
    Files.createDirectories(root.resolve(".reqws"))
    gitRepository(root, "repo-c")
    val state = ReqwsManagedModelState()
    awaitUpdate {
      WorkspaceExcludeModelAdapter(
        project,
        state,
        isTrusted = { true },
        markerTokenFactory = tokenFactory(TOKEN_A, TOKEN_B),
      ).apply(snapshot(root, emptyList()))
    }
    var trusted = true
    var durableState: ReqwsManagedModelState.Data? = null

    val failure = expectApplyFailure {
      WorkspaceExcludeModelAdapter(
        project,
        state,
        isTrusted = { trusted },
        afterDurableStatePersisted = {
          durableState = state.state
          assertTrue(targetExcludedRelativePaths(root).contains("repo-c"))
          assertEquals(mapOf("repo-c" to setOf(TOKEN_B)), recoveryMap(state))
          trusted = false
        },
      ).apply(snapshot(root, listOf("repo-c")))
    }

    assertEquals(ProjectModelErrorCode.UNTRUSTED_PROJECT, failure.code)
    assertTrue(targetExcludedRelativePaths(root).contains("repo-c"))
    assertEquals(mapOf(".reqws" to TOKEN_A), ownershipMap(state))
    assertEquals(mapOf("repo-c" to setOf(TOKEN_B)), recoveryMap(state))

    val reloadedState = ReqwsManagedModelState().also {
      it.loadState(requireNotNull(durableState))
    }
    val recovered = awaitUpdate {
      WorkspaceExcludeModelAdapter(
        project,
        reloadedState,
        isTrusted = { true },
      ).apply(snapshot(root, listOf("repo-c")))
    }

    assertEquals(setOf("repo-c"), recovered.removed)
    assertFalse(targetExcludedRelativePaths(root).contains("repo-c"))
    assertEquals(mapOf(".reqws" to TOKEN_A), ownershipMap(reloadedState))
    assertTrue(pendingRemoveMap(reloadedState).isEmpty())
    assertEquals(mapOf("repo-c" to setOf(TOKEN_B)), recoveryMap(reloadedState))
  }

  fun testRecoversDurableAddIntentAfterTrustChangeAndStateReload() {
    val root = rootPath()
    Files.createDirectories(root.resolve(".reqws"))
    val state = ReqwsManagedModelState()
    val unchangedSnapshot = snapshot(root, emptyList())
    var trusted = true
    val durableStates = mutableListOf<ReqwsManagedModelState.Data>()

    val failure = expectApplyFailure {
      WorkspaceExcludeModelAdapter(
        project,
        state,
        isTrusted = { trusted },
        markerTokenFactory = tokenFactory(TOKEN_A),
        afterDurableStatePersisted = {
          durableStates.add(state.state)
          trusted = false
        },
      ).apply(unchangedSnapshot)
    }

    assertEquals(ProjectModelErrorCode.UNTRUSTED_PROJECT, failure.code)
    assertEquals(1, durableStates.size)
    assertEquals(mapOf(".reqws" to TOKEN_A), ownershipMap(durableStates.single()))
    assertFalse(targetExcludedRelativePaths(root).contains(".reqws"))
    assertEquals(mapOf(".reqws" to TOKEN_A), ownershipMap(state))

    val reloadedState = ReqwsManagedModelState().also { it.loadState(durableStates.last()) }
    val recovered = awaitUpdate {
      WorkspaceExcludeModelAdapter(
        project,
        reloadedState,
        isTrusted = { true },
        afterDurableStatePersisted = {},
      ).apply(unchangedSnapshot)
    }

    assertEquals(setOf(".reqws"), recovered.added)
    assertEquals(mapOf(".reqws" to TOKEN_A), ownershipMap(reloadedState))
    assertTrue(pendingAddMap(reloadedState).isEmpty())
  }

  fun testRetainsFinalIntentWhenTrustChangesAfterOwnershipMirror() {
    val root = rootPath()
    Files.createDirectories(root.resolve(".reqws"))
    val state = ReqwsManagedModelState()

    val failure = expectApplyFailure {
      WorkspaceExcludeModelAdapter(
        project,
        state,
        isTrusted = { ownershipMap(state).isEmpty() },
        markerTokenFactory = tokenFactory(TOKEN_A),
        afterDurableStatePersisted = {},
      ).apply(snapshot(root, emptyList()))
    }

    assertEquals(ProjectModelErrorCode.UNTRUSTED_PROJECT, failure.code)
    assertFalse(targetExcludedRelativePaths(root).contains(".reqws"))
    assertEquals(mapOf(".reqws" to TOKEN_A), ownershipMap(state))
    assertTrue(pendingAddMap(state).isEmpty())
  }

  fun testRecoversDurableAddsAfterTrustChangeAndRepositoryActivation() {
    val root = rootPath()
    Files.createDirectories(root.resolve(".reqws"))
    gitRepository(root, "repo-c")
    val state = ReqwsManagedModelState()
    var trusted = true

    val failure = expectApplyFailure {
      WorkspaceExcludeModelAdapter(
        project,
        state,
        isTrusted = { trusted },
        markerTokenFactory = tokenFactory(TOKEN_A, TOKEN_B),
        afterDurableStatePersisted = { trusted = false },
      ).apply(snapshot(root, listOf("repo-a")))
    }

    assertEquals(ProjectModelErrorCode.UNTRUSTED_PROJECT, failure.code)
    assertFalse(targetExcludedRelativePaths(root).contains(".reqws"))
    assertFalse(targetExcludedRelativePaths(root).contains("repo-c"))
    assertTrue(markerRelativePaths(root).isEmpty())
    assertEquals(
      mapOf(".reqws" to TOKEN_A, "repo-c" to TOKEN_B),
      ownershipMap(state),
    )

    val reloadedState = ReqwsManagedModelState().also { it.loadState(state.state) }
    val recovered = awaitUpdate {
      WorkspaceExcludeModelAdapter(
        project,
        reloadedState,
        isTrusted = { true },
      ).apply(snapshot(root, listOf("repo-c")))
    }

    assertEquals(setOf(".reqws"), recovered.added)
    assertTrue(recovered.removed.isEmpty())
    assertEquals(mapOf(".reqws" to TOKEN_A), ownershipMap(reloadedState))
    assertTrue(pendingAddMap(reloadedState).isEmpty())
    assertTrue(pendingRemoveMap(reloadedState).isEmpty())
    assertEquals(mapOf("repo-c" to setOf(TOKEN_B)), recoveryMap(reloadedState))
    assertFalse(targetExcludedRelativePaths(root).contains("repo-c"))
    assertFalse(markerRelativePaths(root).contains(markerRelative(TOKEN_B)))
  }

  fun testRecoversDurableAddIntentAfterDisposeAndStateReload() {
    val root = rootPath()
    Files.createDirectories(root.resolve(".reqws"))
    val state = ReqwsManagedModelState()
    var disposed = false
    val durableStates = mutableListOf<ReqwsManagedModelState.Data>()

    val failure = expectApplyFailure {
      WorkspaceExcludeModelAdapter(
        project,
        state,
        isTrusted = { true },
        isProjectDisposed = { disposed },
        markerTokenFactory = tokenFactory(TOKEN_A),
        afterDurableStatePersisted = {
          durableStates.add(state.state)
          disposed = true
        },
      ).apply(snapshot(root, emptyList()))
    }

    assertEquals(ProjectModelErrorCode.PROJECT_DISPOSED, failure.code)
    assertEquals(1, durableStates.size)
    assertEquals(mapOf(".reqws" to TOKEN_A), ownershipMap(durableStates.single()))
    assertEquals(mapOf(".reqws" to TOKEN_A), ownershipMap(state))
    assertFalse(targetExcludedRelativePaths(root).contains(".reqws"))

    val reloadedState = ReqwsManagedModelState().also { it.loadState(durableStates.last()) }
    val recovered = awaitUpdate {
      WorkspaceExcludeModelAdapter(
        project,
        reloadedState,
        isTrusted = { true },
        isProjectDisposed = { false },
        afterDurableStatePersisted = {},
      ).apply(snapshot(root, emptyList()))
    }

    assertEquals(setOf(".reqws"), recovered.added)
    assertEquals(mapOf(".reqws" to TOKEN_A), ownershipMap(reloadedState))
    assertTrue(pendingAddMap(reloadedState).isEmpty())
  }

  fun testRecoversDurableRemovalAfterTrustChangeAndReaddsWithANewToken() {
    val root = rootPath()
    Files.createDirectories(root.resolve(".reqws"))
    gitRepository(root, "repo-c")
    val originalState = ReqwsManagedModelState()
    awaitUpdate {
      WorkspaceExcludeModelAdapter(
        project,
        originalState,
        isTrusted = { true },
        markerTokenFactory = tokenFactory(TOKEN_A, TOKEN_B),
      ).apply(snapshot(root, emptyList()))
    }
    var trusted = true

    val failure = expectApplyFailure {
      WorkspaceExcludeModelAdapter(
        project,
        originalState,
        isTrusted = { trusted },
        afterDurableStatePersisted = { trusted = false },
      ).apply(snapshot(root, listOf("repo-c")))
    }

    assertEquals(ProjectModelErrorCode.UNTRUSTED_PROJECT, failure.code)
    assertEquals(mapOf(".reqws" to TOKEN_A), ownershipMap(originalState))
    assertEquals(mapOf("repo-c" to setOf(TOKEN_B)), recoveryMap(originalState))
    assertTrue(targetExcludedRelativePaths(root).contains("repo-c"))
    assertTrue(markerRelativePaths(root).contains(markerRelative(TOKEN_B)))

    val reloadedState = ReqwsManagedModelState().also { it.loadState(originalState.state) }
    val recoveryAdapter = WorkspaceExcludeModelAdapter(
      project,
      reloadedState,
      isTrusted = { true },
      markerTokenFactory = tokenFactory(TOKEN_C),
    )
    awaitUpdate { recoveryAdapter.apply(snapshot(root, listOf("repo-c"))) }

    assertTrue(pendingRemoveMap(reloadedState).isEmpty())
    assertEquals(mapOf(".reqws" to TOKEN_A), ownershipMap(reloadedState))
    assertEquals(mapOf("repo-c" to setOf(TOKEN_B)), recoveryMap(reloadedState))

    val readded = awaitUpdate { recoveryAdapter.apply(snapshot(root, emptyList())) }

    assertEquals(setOf("repo-c"), readded.added)
    assertEquals(
      mapOf(".reqws" to TOKEN_A, "repo-c" to TOKEN_C),
      ownershipMap(reloadedState),
    )
    assertEquals(mapOf("repo-c" to setOf(TOKEN_B)), recoveryMap(reloadedState))
    assertEquals(
      setOf(markerRelative(TOKEN_A), markerRelative(TOKEN_C)),
      markerRelativePaths(root),
    )
  }

  fun testTraceSpansCoverRealModelAndPfiAndKeepTheApplyIdentity() {
    val root = rootPath()
    Files.createDirectories(root.resolve(".reqws"))
    gitRepository(root, "repo-a")
    val lines = mutableListOf<String>()
    val trace = ReqwsSyncTrace.testing(sink = lines::add)
    val adapter = WorkspaceExcludeModelAdapter(
      project, ReqwsManagedModelState(), isTrusted = { true }, trace = trace,
    )
    val result = awaitUpdate {
      withContext(trace.workerContext()) {
        trace.withAttempt(31, 41) { adapter.apply(snapshot(root, listOf("repo-a"))) }
      }
    }
    assertEquals(setOf(".reqws"), result.added)
    assertLiveProjection(root, included = setOf("repo-a"), excluded = emptySet())
    assertProjectionTrace(lines, listOf("MODEL", "PFI"), "SUCCESS")
    assertTrue(traceRecords(lines, SyncTraceEvent.PROJECTION_STAGE_END).all { it["request_id"] == "31" && it["source_id"] == "41" })
  }

  fun testTraceStopsAtTheFailingProjectionStageWithoutInventingLaterStages() {
    assertTraceProjectionFailure("MODEL", IllegalStateException("model failure"))
  }

  fun testTracePfiFailureFinishesThePfiStage() {
    assertTraceProjectionFailure("PFI", IllegalStateException("pfi failure"))
  }

  fun testTracePreservesPfiPlatformCancellation() {
    assertTraceProjectionFailure("PFI", ProcessCanceledException())
  }

  fun testTracePreservesPfiCoroutineCancellation() {
    assertTraceProjectionFailure("PFI", CancellationException("pfi cancellation"))
  }

  private fun assertTraceProjectionFailure(stage: String, failure: Throwable) {
    val root = rootPath()
    Files.createDirectories(root.resolve(".reqws"))
    gitRepository(root, "repo-a")
    val lines = mutableListOf<String>()
    val adapter = WorkspaceExcludeModelAdapter(
      project, ReqwsManagedModelState(),
      isTrusted = { if (stage == "MODEL") throw failure else true },
      liveProjectionVerifier = ReqwsLiveProjectionVerifier { _, _ -> if (stage == "PFI") throw failure },
      trace = ReqwsSyncTrace.testing(sink = lines::add),
    )
    // Catch within the worker coroutine to preserve the original exception across the platform future.
    val caught = awaitUpdate {
      try { adapter.apply(snapshot(root, listOf("repo-a"))); null } catch (thrown: Throwable) { thrown }
    }
    assertSame(failure, caught)
    val enteredStages = listOf("MODEL", "PFI").takeWhile { it != stage } + stage
    assertProjectionTrace(lines, enteredStages, if (failure is ProcessCanceledException || failure is CancellationException) "CANCELLED" else "FAILED")
  }

  private fun assertProjectionTrace(lines: List<String>, stages: List<String>, lastOutcome: String) {
    val starts = traceRecords(lines, SyncTraceEvent.PROJECTION_STAGE_START)
    val ends = traceRecords(lines, SyncTraceEvent.PROJECTION_STAGE_END)
    assertEquals(stages, starts.map { it["stage"] })
    assertEquals(stages, ends.map { it["stage"] })
    assertEquals(starts.map { it["span_id"] }, ends.map { it["span_id"] })
    assertEquals(stages.size, starts.map { it["span_id"] }.distinct().size)
    assertTrue(ends.dropLast(1).all { it["outcome"] == "SUCCESS" })
    assertEquals(lastOutcome, ends.last()["outcome"])
    assertTrue(ends.all { requireNotNull(it["elapsed_nanos"]).toLong() >= 0 })
  }

  private fun rootPath(): Path {
    localRoot?.let { return it }
    val root = Files.createTempDirectory("reqws-project-model-test").toRealPath()
    Files.createDirectory(root.resolve(".idea"))
    localRoot = root
    val workspaceModel = WorkspaceModel.getInstance(project)
    val rootUrl = workspaceModel.getVirtualFileUrlManager().fromPath(root.toString())
    awaitUpdate {
      workspaceModel.update("Seed ReqWS workspace root") { storage ->
        val moduleEntity = requireNotNull(storage.resolve(ModuleId(module.name)))
        storage.modifyModuleEntity(moduleEntity) {
          contentRoots = contentRoots + ContentRootEntity(rootUrl, emptyList(), moduleEntity.entitySource)
        }
      }
    }
    return root
  }

  private fun gitRepository(root: Path, name: String) {
    Files.createDirectories(root.resolve(name).resolve(".git"))
  }

  private fun addContentRoot(path: Path) {
    val workspaceModel = WorkspaceModel.getInstance(project)
    val contentRootUrl = workspaceModel.getVirtualFileUrlManager().fromPath(path.toString())
    awaitUpdate {
      workspaceModel.update("Seed nested Content Root") { storage ->
        val moduleEntity = requireNotNull(storage.resolve(ModuleId(module.name)))
        storage.modifyModuleEntity(moduleEntity) {
          contentRoots = contentRoots + ContentRootEntity(
            contentRootUrl,
            emptyList(),
            moduleEntity.entitySource,
          )
        }
      }
    }
  }

  private fun addExclude(relative: String) {
    val root = rootPath()
    val workspaceModel = WorkspaceModel.getInstance(project)
    val workspaceUrl = workspaceModel.getVirtualFileUrlManager().fromPath(root.toString())
    val excludeUrl = workspaceModel.getVirtualFileUrlManager().fromPath(root.resolve(relative).toString())
    awaitUpdate {
      workspaceModel.update("Seed project model exclude") { storage ->
        val moduleEntity = requireNotNull(storage.resolve(ModuleId(module.name)))
        val contentRoot = requireNotNull(
          moduleEntity.contentRoots.singleOrNull { it.url.url == workspaceUrl.url },
        )
        storage.modifyContentRootEntity(contentRoot) {
          excludedUrls = excludedUrls + ExcludeUrlEntity(excludeUrl, contentRoot.entitySource)
        }
      }
    }
  }

  private fun removeExclude(relative: String) {
    val root = rootPath()
    val workspaceModel = WorkspaceModel.getInstance(project)
    val workspaceUrl = workspaceModel.getVirtualFileUrlManager().fromPath(root.toString())
    val excludeUrl = workspaceModel.getVirtualFileUrlManager().fromPath(root.resolve(relative).toString()).url
    awaitUpdate {
      workspaceModel.update("Remove project model exclude") { storage ->
        val moduleEntity = requireNotNull(storage.resolve(ModuleId(module.name)))
        val contentRoot = requireNotNull(
          moduleEntity.contentRoots.singleOrNull { it.url.url == workspaceUrl.url },
        )
        storage.modifyContentRootEntity(contentRoot) {
          excludedUrls = excludedUrls.filter { it.url.url != excludeUrl }
        }
      }
    }
  }

  private fun targetExcludedRelativePaths(root: Path): Set<String> =
    targetExcludedRelativePathsList(root).toSet()

  private fun targetExcludedRelativePathsList(root: Path): List<String> =
    excludedRelativePathsList(root).filterNot { it.startsWith(MARKER_PREFIX) }

  private fun markerRelativePaths(root: Path): Set<String> = excludedRelativePathsList(root)
    .filterTo(linkedSetOf()) { it.startsWith(MARKER_PREFIX) }

  private fun excludedRelativePathsList(root: Path): List<String> = excludedUrls().map { url ->
    root.relativize(Path.of(url.removePrefix("file://"))).toString()
  }

  private fun excludedUrls(): List<String> {
    val root = rootPath()
    val workspaceModel = WorkspaceModel.getInstance(project)
    val workspaceUrl = workspaceModel.getVirtualFileUrlManager().fromPath(root.toString()).url
    val moduleEntity = requireNotNull(workspaceModel.currentSnapshot.resolve(ModuleId(module.name)))
    val contentRoot = requireNotNull(
      moduleEntity.contentRoots.singleOrNull { it.url.url == workspaceUrl },
    )
    return contentRoot.excludedUrls.map { it.url.url }.sorted()
  }

  private fun assertLiveProjection(
    root: Path,
    included: Set<String>,
    excluded: Set<String>,
  ) {
    val fileIndex = ProjectFileIndex.getInstance(project)
    included.forEach { relative ->
      val file = requireNotNull(LocalFileSystem.getInstance().refreshAndFindFileByNioFile(root.resolve(relative)))
      assertTrue("$relative should be in project content", fileIndex.isInContent(file))
      assertFalse("$relative should not be excluded", fileIndex.isExcluded(file))
    }
    excluded.forEach { relative ->
      val file = requireNotNull(LocalFileSystem.getInstance().refreshAndFindFileByNioFile(root.resolve(relative)))
      assertTrue("$relative should be excluded", fileIndex.isExcluded(file))
    }
  }

  private fun ownershipMap(state: ReqwsManagedModelState): Map<String, String> =
    state.ownership().managedExcludes.associate { it.relativePath to it.markerToken }

  private fun ownershipMap(state: ReqwsManagedModelState.Data): Map<String, String> =
    state.managedExcludes.associate { it.relativePath to it.markerToken }

  private fun pendingAddMap(state: ReqwsManagedModelState): Map<String, String> =
    state.ownership().pendingAdds.associate { it.relativePath to it.markerToken }

  private fun pendingAddMap(state: ReqwsManagedModelState.Data): Map<String, String> =
    state.pendingAdds.associate { it.relativePath to it.markerToken }

  private fun pendingRemoveMap(state: ReqwsManagedModelState): Map<String, String> =
    state.ownership().pendingRemovals.associate { it.relativePath to it.markerToken }

  private fun recoveryMap(state: ReqwsManagedModelState): Map<String, Set<String>> =
    state.ownership().recoveryClaims
      .groupBy(ManagedExcludeOwnership::relativePath)
      .mapValues { (_, claims) -> claims.mapTo(linkedSetOf(), ManagedExcludeOwnership::markerToken) }

  private fun persistedClaim(
    relativePath: String,
    markerToken: String,
  ): ReqwsManagedModelState.PersistedManagedExclude =
    ReqwsManagedModelState.PersistedManagedExclude().also { persisted ->
      persisted.relativePath = relativePath
      persisted.markerToken = markerToken
    }

  private fun durableState(
    root: Path,
    writerJvmEpoch: String,
    targetModuleName: String = "",
    managedClaims: List<DurableManagedClaim> = emptyList(),
    recoveryClaims: List<DurableManagedClaim> = emptyList(),
  ): DurableManagedModelState {
    val binding = managedModelStateBinding("ws_test", root)
    return DurableManagedModelState(
      workspaceId = binding.workspaceId,
      rootFingerprint = binding.rootFingerprint,
      generation = 0L,
      writerJvmEpoch = writerJvmEpoch,
      targetModuleName = targetModuleName,
      managedClaims = managedClaims,
      recoveryClaims = recoveryClaims,
    )
  }

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
        canonicalPath = path.takeIf(Files::isDirectory)?.toRealPath(),
        availability = if (Files.isDirectory(path)) {
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

  private fun tokenFactory(vararg tokens: String): () -> String {
    val iterator = tokens.iterator()
    return { iterator.next() }
  }

  private fun markerRelative(token: String): String = "$MARKER_PREFIX/$token"

  private fun <T> awaitUpdate(block: suspend () -> T): T {
    val future = AppExecutorUtil.getAppExecutorService().submit(Callable {
      runBlocking { block() }
    })
    return PlatformTestUtil.waitForFuture(future)
  }

  private fun expectApplyFailure(block: suspend () -> Unit): ProjectModelApplyException {
    try {
      awaitUpdate(block)
    } catch (exception: Throwable) {
      var current: Throwable? = exception
      while (current != null) {
        if (current is ProjectModelApplyException) return current
        current = current.cause
      }
      throw exception
    }
    throw AssertionError("Expected ProjectModelApplyException")
  }

  companion object {
    private const val MARKER_PREFIX = ".reqws/.goland-ownership"
    private const val TOKEN_A = "11111111111111111111111111111111"
    private const val TOKEN_B = "22222222222222222222222222222222"
    private const val TOKEN_C = "33333333333333333333333333333333"
    private const val EPOCH_A =
      "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
    private const val EPOCH_B =
      "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
  }
}
