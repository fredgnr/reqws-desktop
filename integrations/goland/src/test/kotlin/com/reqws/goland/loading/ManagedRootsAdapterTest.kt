package com.reqws.goland.loading

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.intellij.platform.backend.workspace.WorkspaceModel
import com.intellij.platform.workspace.jps.entities.ContentRootEntity
import com.intellij.platform.workspace.jps.entities.ExcludeUrlEntity
import com.intellij.platform.workspace.jps.entities.ModuleEntity
import com.intellij.platform.workspace.jps.entities.ModuleId
import com.intellij.platform.workspace.jps.entities.SourceRootEntity
import com.intellij.platform.workspace.jps.entities.SourceRootTypeId
import com.intellij.platform.workspace.jps.entities.modifyContentRootEntity
import com.intellij.platform.workspace.jps.entities.modifyModuleEntity
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.HeavyPlatformTestCase
import com.intellij.util.concurrency.AppExecutorUtil
import com.reqws.goland.loading.contract.LoadingSnapshotReader
import com.reqws.goland.loading.model.ManagedRootsAdapter
import com.reqws.goland.loading.model.ROOT_MARKER_NAMESPACE
import com.reqws.goland.loading.model.rootsJournalFile
import com.reqws.goland.projectmodel.ProjectModelApplyException
import com.reqws.goland.projectmodel.ProjectModelErrorCode
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.Callable

class ManagedRootsAdapterTest : HeavyPlatformTestCase() {
  private lateinit var root: Path
  private lateinit var shell: Path
  private val bindingId = "95dc7c6a-0eaa-4c96-824a-e117316a1db3"
  private val moduleName get() = "ReqWS-$bindingId"
  private val reader = LoadingSnapshotReader()

  override fun setUp() {
    super.setUp()
    root = Files.createTempDirectory("reqws-loaded-roots-").toRealPath()
    shell = root.resolve(".reqws/ide/goland")
    Files.createDirectories(shell.resolve(".idea"))
    for (name in listOf("one", "two", "extra")) {
      Files.createDirectories(root.resolve("$name/.git"))
      Files.writeString(root.resolve("$name/probe.txt"), name)
    }
    Files.writeString(root.resolve(".reqws/workspace.json"), JsonObject().apply {
      addProperty("schemaVersion", 1); addProperty("id", "ws_1"); addProperty("name", "fixture")
      addProperty("featureBranch", "feature/test"); addProperty("rootPath", root.toString())
      addProperty("workspaceFilePath", root.resolve("fixture.code-workspace").toString())
      addProperty("createdAt", "2026-09-19T00:00:00Z"); addProperty("updatedAt", "2026-09-19T00:00:00Z")
      add("repositories", JsonArray().apply { for (name in listOf("one", "two")) add(JsonObject().apply {
        addProperty("catalogRepositoryId", name); addProperty("name", name); addProperty("relativePath", name)
        addProperty("url", "https://example.com/$name.git"); addProperty("defaultBranch", "main")
      }) })
    }.toString())
    select("one", "two")
  }

  override fun tearDown() {
    try { super.tearDown() } finally { if (::root.isInitialized) root.toFile().deleteRecursively() }
  }

  fun testInitialJpsWaitPrecedesIntentAndDoesNotHoldTheWriterLock() = awaitUpdate {
    coroutineScope {
      val entered = CompletableDeferred<Unit>()
      val release = CompletableDeferred<Unit>()
      val applying = async {
        ManagedRootsAdapter(project, { true }, awaitInitialJps = {
          entered.complete(Unit)
          release.await()
        }).apply(reader.read(shell))
      }
      entered.await()
      assertNoManagedIntent()
      rootsJournalFile(shell).withStableParent { requireNotNull(it.tryAcquireExclusiveDirectoryLock()).close() }
      release.complete(Unit)
      assertEquals(setOf("one", "two"), applying.await().owned)
    }
  }

  fun testCancelledInitialJpsWaitCannotWriteIntentOrCreateRoots() = awaitUpdate {
    coroutineScope {
      val entered = CompletableDeferred<Unit>()
      val applying = async {
        ManagedRootsAdapter(project, { true }, awaitInitialJps = {
          entered.complete(Unit)
          kotlinx.coroutines.awaitCancellation()
        }).apply(reader.read(shell))
      }
      entered.await()
      applying.cancelAndJoin()
      assertNoManagedIntent()
    }
  }

  fun testTrustOrGenerationRevocationDuringInitialJpsWaitCannotWrite() {
    var allowed = true
    val adapter = ManagedRootsAdapter(project, { allowed }, awaitInitialJps = { allowed = false })
    val failure = awaitUpdate {
      try { adapter.apply(reader.read(shell)); null }
      catch (failure: kotlinx.coroutines.CancellationException) { failure }
    }
    assertNotNull(failure)
    assertNoManagedIntent()
  }

  fun testBindingChangeDuringInitialJpsWaitCannotUseTheOldSelection() {
    expectConflict(ManagedRootsAdapter(project, { true }, awaitInitialJps = { select() }))
    assertNoManagedIntent()
  }

  fun testMissingOwnedRootAfterInitialJpsWaitCannotBeReclaimed() {
    apply(ManagedRootsAdapter(project, { true }))
    val journal = rootsJournalFile(shell).read()!!
    val adapter = ManagedRootsAdapter(project, { true }, awaitInitialJps = {
      WorkspaceModel.getInstance(project).update("Fixture JPS model replacement") { storage ->
        val owned = storage.resolve(ModuleId(moduleName))!!.contentRoots.single { it.url.url.endsWith("/one") }
        storage.removeEntity(owned)
      }
    })
    expectConflict(adapter)
    assertEquals(setOf("two"), contentNames())
    assertEquals(journal, rootsJournalFile(shell).read())
  }

  private fun assertNoManagedIntent() {
    assertNull(WorkspaceModel.getInstance(project).currentSnapshot.resolve(ModuleId(moduleName)))
    assertFalse(Files.exists(shell.resolve(".idea/reqws-loaded-roots.json")))
    assertFalse(Files.exists(shell.resolve(".idea/reqws")))
    assertFalse(project.getService(com.reqws.goland.projectmodel.ReqwsProjectModelMutationGuard::class.java).isActive)
  }

  fun testTwoOneZeroTwoPreservesUserRootInManagedModule() {
    val adapter = ManagedRootsAdapter(project, { true })
    assertEquals(setOf("one", "two"), apply(adapter).owned)
    val platformType = com.intellij.openapi.module.ModuleTypeManager.getInstance().defaultModuleType.id
    assertEquals(platformType, WorkspaceModel.getInstance(project).currentSnapshot.resolve(ModuleId(moduleName))!!.type!!.name)
    addRoot("extra")
    select("one")
    assertEquals(setOf("one"), apply(adapter).owned)
    assertEquals(setOf("one", "extra"), contentNames())
    select()
    assertTrue(apply(adapter).owned.isEmpty())
    assertEquals(setOf("extra"), contentNames())
    assertNotNull(WorkspaceModel.getInstance(project).currentSnapshot.resolve(ModuleId(moduleName)))
    // Fresh adapter re-reads journal and live model; it does not restore a Synced digest.
    assertTrue(apply(ManagedRootsAdapter(project, { true })).owned.isEmpty())
    select("one", "two")
    assertEquals(setOf("one", "two"), apply(adapter).owned)
    assertEquals(setOf("one", "two", "extra"), contentNames())
    assertEquals("two", Files.readString(root.resolve("two/probe.txt")))
  }

  fun testBorrowedRootIsNeverClaimedOrRemoved() {
    select()
    val adapter = ManagedRootsAdapter(project, { true })
    apply(adapter)
    addRoot("one")
    select("one")
    assertEquals(setOf("one"), apply(adapter).borrowed)
    assertTrue(rootsJournalFile(shell).read()!!.claims.isEmpty())
    select()
    assertEquals(setOf("one"), apply(adapter).userCoverage)
    assertEquals(setOf("one"), contentNames())
  }

  fun testSameDigestAutomaticRefreshKeepsVerifiedUserRootCoverage() =
    verifySameDigestAutomaticRefreshKeepsVerifiedUserRootCoverage()

  private fun verifySameDigestAutomaticRefreshKeepsVerifiedUserRootCoverage() {
    select()
    apply(ManagedRootsAdapter(project, { true }))
    addRoot("one")
    val model = WorkspaceModel.getInstance(project)
    update { storage ->
      val native = storage.resolve(ModuleId(module.name))!!
      storage.modifyModuleEntity(native) { contentRoots += ContentRootEntity(model.getVirtualFileUrlManager().fromPath(shell.toString()), emptyList(), native.entitySource) }
    }
    val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Default)
    val applies = java.util.concurrent.atomic.AtomicInteger()
    val successes = java.util.concurrent.atomic.AtomicInteger()
    val service = com.reqws.goland.project.ReqwsProjectService.createForTest(project, scope,
      com.reqws.goland.project.ReqwsProjectServiceRuntimeOverrides(
        projectRoot = shell,
        trustGate = com.reqws.goland.project.ReqwsTrustGate { true },
        candidateApplier = com.reqws.goland.sync.SyncCandidateApplier { candidate ->
          project.getService(com.reqws.goland.loading.model.LoadedProjectionService::class.java).apply(requireNotNull(candidate.value.loading)) { true }
          applies.incrementAndGet()
        },
        vcsChangeRegistrar = com.reqws.goland.project.ReqwsVcsChangeRegistrar { AutoCloseable {} },
        vcsInspector = com.reqws.goland.project.ReqwsVcsInspector { com.reqws.goland.vcs.VcsRootInspection(emptyList(), emptyList()) },
        manifestWatcherFactory = com.reqws.goland.project.ReqwsManifestWatcherFactory { _, _, _, _ -> com.intellij.openapi.Disposable {} },
      ),
    )
    val listener = service.addListener { state ->
      if (state.lifecycle == com.reqws.goland.project.ReqwsLifecycleState.SYNCHRONIZED && state.validatedProjectionDigest != null) successes.incrementAndGet()
    }
    try {
      repeat(2) {
        val previous = successes.get()
        awaitUpdate {
          requireNotNull(service.refreshAutomatically()).join()
          kotlinx.coroutines.withTimeout(5000) { while (successes.get() <= previous) kotlinx.coroutines.delay(10) }
        }
        assertEquals(setOf("one"), service.state.userRootCoverage)
        assertEquals(1, applies.get())
        assertEquals(setOf("one"), contentNames())
      }
    } finally {
      listener.close()
      service.dispose()
      scope.coroutineContext[kotlinx.coroutines.Job]!!.cancel()
    }
  }

  fun testPlatformSaveAndReloadPreservesMarkersAndExtraRoot() {
    val adapter = ManagedRootsAdapter(project, { true })
    apply(adapter)
    addRoot("extra")
    PlatformTestUtil.saveProject(project, true)
    val journal = rootsJournalFile(shell).read()!!
    val moduleXml = Files.readString(Path.of(journal.moduleFile))
    assertTrue(moduleXml.contains("type=\"${com.intellij.openapi.module.ModuleTypeManager.getInstance().defaultModuleType.id}\""))
    journal.claims.forEach { claim -> assertTrue(moduleXml.contains("$ROOT_MARKER_NAMESPACE/${claim.nonce}")) }
    assertTrue(moduleXml.contains("extra"))
    val projectLocation = Path.of(project.presentableUrl!!)
    PlatformTestUtil.forceCloseProjectWithoutSaving(project)
    myProject = PlatformTestUtil.loadAndOpenProject(projectLocation, testRootDisposable)
    myModule = com.intellij.openapi.module.ModuleManager.getInstance(project).modules.first()
    val recovered = apply(ManagedRootsAdapter(project, { true }))
    assertEquals(setOf("one", "two"), recovered.owned)
    assertEquals(setOf("one", "two", "extra"), contentNames())
    select()
    apply(ManagedRootsAdapter(project, { true }))
    assertEquals(setOf("extra"), contentNames())
    PlatformTestUtil.saveProject(project, true)
    PlatformTestUtil.forceCloseProjectWithoutSaving(project)
    myProject = PlatformTestUtil.loadAndOpenProject(projectLocation, testRootDisposable)
    myModule = com.intellij.openapi.module.ModuleManager.getInstance(project).modules.first()
    assertTrue(apply(ManagedRootsAdapter(project, { true })).owned.isEmpty())
    assertEquals(setOf("extra"), contentNames())
    select("one", "two")
    assertEquals(setOf("one", "two"), apply(ManagedRootsAdapter(project, { true })).owned)
  }

  fun testReplacementBindingRevokesPresentationButPreservesRoots() {
    val model = WorkspaceModel.getInstance(project)
    update { storage ->
      val native = storage.resolve(ModuleId(module.name))!!
      storage.modifyModuleEntity(native) { contentRoots += ContentRootEntity(model.getVirtualFileUrlManager().fromPath(shell.toString()), emptyList(), native.entitySource) }
    }
    val projection = project.getService(com.reqws.goland.loading.model.LoadedProjectionService::class.java)
    awaitUpdate { projection.apply(reader.read(shell)) { true } }
    assertNotNull(project.getService(com.reqws.goland.loading.shell.ShellPresentationCache::class.java).current())
    val binding = shell.resolve("reqws-project.json")
    Files.writeString(binding, Files.readString(binding).replace(bindingId, "a5dc7c6a-0eaa-4c96-824a-e117316a1db3"))
    val failure = awaitUpdate { try { projection.apply(reader.read(shell)) { true }; null } catch (failure: Exception) { failure } }
    assertNotNull(failure)
    assertNull(project.getService(com.reqws.goland.loading.shell.ShellPresentationCache::class.java).current())
    assertEquals(setOf("one", "two"), contentNames())
  }

  fun testRepeatedLiveReconciliationRetainsRemovalIntentForAnOlderSavedModel() {
    val adapter = ManagedRootsAdapter(project, { true })
    apply(adapter)
    PlatformTestUtil.saveProject(project, true)
    val journal = rootsJournalFile(shell).read()!!
    val savedModule = Files.readAllBytes(Path.of(journal.moduleFile))
    select()
    apply(adapter)
    apply(adapter)
    assertEquals(2, rootsJournalFile(shell).read()!!.pendingRemoves.size)
    val projectLocation = Path.of(project.presentableUrl!!)
    PlatformTestUtil.forceCloseProjectWithoutSaving(project)
    // Simulate a crash where the journal committed but the platform still has its earlier .iml.
    Files.write(Path.of(journal.moduleFile), savedModule)
    myProject = PlatformTestUtil.loadAndOpenProject(projectLocation, testRootDisposable)
    myModule = com.intellij.openapi.module.ModuleManager.getInstance(project).modules.first()
    assertEquals(setOf("one", "two"), contentNames())
    val recovered = apply(ManagedRootsAdapter(project, { true }))
    assertTrue(recovered.owned.isEmpty())
    assertTrue(recovered.userCoverage.isEmpty())
    assertTrue(contentNames().isEmpty())
  }

  fun testMissingMarkerBlocksRemoval() {
    val adapter = ManagedRootsAdapter(project, { true })
    apply(adapter)
    update { storage ->
      val one = storage.resolve(ModuleId(moduleName))!!.contentRoots.single { it.url.url.endsWith("/one") }
      storage.modifyContentRootEntity(one) { excludedUrls = emptyList() }
    }
    select()
    expectConflict(adapter)
    assertEquals(setOf("one", "two"), contentNames())
  }

  fun testUserExcludeBlocksCascadingDeletion() {
    val adapter = ManagedRootsAdapter(project, { true })
    apply(adapter)
    val model = WorkspaceModel.getInstance(project)
    update { storage ->
      val one = storage.resolve(ModuleId(moduleName))!!.contentRoots.single { it.url.url.endsWith("/one") }
      storage.modifyContentRootEntity(one) {
        excludedUrls += ExcludeUrlEntity(model.getVirtualFileUrlManager().fromPath(root.resolve("one/user-excluded").toString()), one.entitySource)
      }
    }
    select()
    expectConflict(adapter)
    assertEquals(setOf("one", "two"), contentNames())
  }

  fun testMaterializedMarkerNamespaceFailsClosed() {
    val adapter = ManagedRootsAdapter(project, { true })
    apply(adapter)
    Files.createDirectory(root.resolve("one/$ROOT_MARKER_NAMESPACE"))
    select()
    expectConflict(adapter)
    assertEquals(setOf("one", "two"), contentNames())
  }

  fun testUserSourceRootBlocksCascadingDeletion() {
    val adapter = ManagedRootsAdapter(project, { true })
    apply(adapter)
    val urls = WorkspaceModel.getInstance(project).getVirtualFileUrlManager()
    update { storage ->
      val one = storage.resolve(ModuleId(moduleName))!!.contentRoots.single { it.url.url.endsWith("/one") }
      storage.modifyContentRootEntity(one) {
        sourceRoots += SourceRootEntity(urls.fromPath(root.resolve("one/src").toString()), SourceRootTypeId("java-source"), one.entitySource)
      }
    }
    select()
    expectConflict(adapter)
    assertEquals(setOf("one", "two"), contentNames())
    assertEquals(1, WorkspaceModel.getInstance(project).currentSnapshot.entities(SourceRootEntity::class.java).count())
  }

  fun testUserExcludePatternBlocksCascadingDeletion() {
    val adapter = ManagedRootsAdapter(project, { true })
    apply(adapter)
    update { storage ->
      val one = storage.resolve(ModuleId(moduleName))!!.contentRoots.single { it.url.url.endsWith("/one") }
      storage.modifyContentRootEntity(one) { excludedPatterns += "user-output-*" }
    }
    select()
    expectConflict(adapter)
    assertEquals(setOf("one", "two"), contentNames())
    val one = WorkspaceModel.getInstance(project).currentSnapshot.resolve(ModuleId(moduleName))!!.contentRoots.single { it.url.url.endsWith("/one") }
    assertEquals(listOf("user-output-*"), one.excludedPatterns)
  }

  fun testDuplicatedMarkerCannotAuthorizeDeletion() {
    val adapter = ManagedRootsAdapter(project, { true })
    apply(adapter)
    update { storage ->
      val managed = storage.resolve(ModuleId(moduleName))!!
      val marker = managed.contentRoots.single { it.url.url.endsWith("/one") }.excludedUrls.single()
      val two = managed.contentRoots.single { it.url.url.endsWith("/two") }
      storage.modifyContentRootEntity(two) { excludedUrls += ExcludeUrlEntity(marker.url, marker.entitySource) }
    }
    select()
    expectConflict(adapter)
    assertEquals(setOf("one", "two"), contentNames())
    assertEquals(3, WorkspaceModel.getInstance(project).currentSnapshot.entities(ExcludeUrlEntity::class.java).count())
  }

  fun testRepositoryDirectoryReplacementCannotInheritOwnership() {
    val adapter = ManagedRootsAdapter(project, { true })
    apply(adapter)
    Files.move(root.resolve("one"), root.resolve("original-one"))
    Files.createDirectories(root.resolve("one/.git"))
    Files.writeString(root.resolve("one/probe.txt"), "replacement")
    select()
    expectConflict(adapter)
    assertEquals(setOf("one", "two"), contentNames())
    assertEquals("one", Files.readString(root.resolve("original-one/probe.txt")))
    assertEquals("replacement", Files.readString(root.resolve("one/probe.txt")))
  }

  fun testPreviouslySavedModuleFileCannotBecomeFreshUnsavedEvidence() {
    val adapter = ManagedRootsAdapter(project, { true })
    apply(adapter)
    PlatformTestUtil.saveProject(project, true)
    apply(adapter)
    val file = Path.of(rootsJournalFile(shell).read()!!.moduleFile)
    val preserved = file.resolveSibling("preserved.iml")
    Files.move(file, preserved)
    select()
    expectConflict(adapter)
    assertEquals(setOf("one", "two"), contentNames())
    assertTrue(Files.isRegularFile(preserved))
    assertFalse(Files.exists(file))
  }

  fun testCancellationAfterPreparedRemovalKeepsModelAndRecoveryEvidence() {
    apply(ManagedRootsAdapter(project, { true }))
    select()
    val cancelling = ManagedRootsAdapter(project, { true }, verifySnapshot = { snapshot ->
      reader.verifyCurrent(snapshot)
      if (rootsJournalFile(shell).read()!!.pendingRemoves.isNotEmpty()) {
        throw kotlinx.coroutines.CancellationException("Cancel after durable intent")
      }
    })
    val failure = awaitUpdate { try { cancelling.apply(reader.read(shell)); null } catch (failure: kotlinx.coroutines.CancellationException) { failure } }
    assertNotNull(failure)
    assertEquals(setOf("one", "two"), contentNames())
    assertEquals(2, rootsJournalFile(shell).read()!!.pendingRemoves.size)
    assertTrue(apply(ManagedRootsAdapter(project, { true })).owned.isEmpty())
    assertTrue(contentNames().isEmpty())
  }

  fun testCancelledPreparedAdditionRetriesWithFreshAdapterAndPreservesUserConfiguration() {
    select("one")
    apply(ManagedRootsAdapter(project, { true }))
    addRoot("extra")
    update { storage ->
      val extra = storage.resolve(ModuleId(moduleName))!!.contentRoots.single { it.url.url.endsWith("/extra") }
      storage.modifyContentRootEntity(extra) { excludedPatterns += "user-output-*" }
    }
    select("one", "two")
    cancelPreparedAddition()
    assertEquals(setOf("one", "extra"), contentNames())
    assertEquals(setOf("one", "two"), apply(ManagedRootsAdapter(project, { true })).owned)
    assertEquals(setOf("one", "two", "extra"), contentNames())
    val extra = WorkspaceModel.getInstance(project).currentSnapshot.resolve(ModuleId(moduleName))!!.contentRoots.single { it.url.url.endsWith("/extra") }
    assertEquals(listOf("user-output-*"), extra.excludedPatterns)
    assertTrue(extra.excludedUrls.isEmpty())
    assertEquals("extra", Files.readString(root.resolve("extra/probe.txt")))
  }

  fun testCancelledPreparedAdditionCanReturnToPreviousSelection() {
    select("one")
    apply(ManagedRootsAdapter(project, { true }))
    select("one", "two")
    cancelPreparedAddition()
    select("one")
    assertEquals(setOf("one"), apply(ManagedRootsAdapter(project, { true })).owned)
    assertEquals(setOf("one"), contentNames())
    assertFalse(rootsJournalFile(shell).read()!!.pendingAdds.any { it.relativePath == "two" })
  }

  fun testCancelledPreparedModuleCreationRetriesLatestSelection() {
    cancelPreparedAddition()
    assertNull(WorkspaceModel.getInstance(project).currentSnapshot.resolve(ModuleId(moduleName)))
    assertFalse(Files.exists(Path.of(rootsJournalFile(shell).read()!!.moduleFile)))
    select("one")
    assertEquals(setOf("one"), apply(ManagedRootsAdapter(project, { true })).owned)
    assertEquals(setOf("one"), contentNames())
  }

  fun testCancelledAdditionInsideModelUpdaterDoesNotCommitOrBlockRetry() {
    select("one")
    apply(ManagedRootsAdapter(project, { true }))
    select("one", "two")
    cancelPreparedAddition(insideUpdater = true)
    assertEquals(setOf("one"), contentNames())
    assertEquals(setOf("one", "two"), apply(ManagedRootsAdapter(project, { true })).owned)
  }

  fun testCancelledCoroutineDuringUpdaterDoesNotCommitAndCanRetry() {
    select("one")
    apply(ManagedRootsAdapter(project, { true }))
    select("one", "two")
    val failure = awaitUpdate {
      try {
        kotlinx.coroutines.coroutineScope {
          val candidate = kotlinx.coroutines.currentCoroutineContext()[kotlinx.coroutines.Job]!!
          val cancelling = ManagedRootsAdapter(project, { true }, verifySnapshot = { snapshot ->
            reader.verifyCurrent(snapshot)
            if (project.getService(com.reqws.goland.projectmodel.ReqwsProjectModelMutationGuard::class.java).isActive) {
              candidate.cancel()
            }
          })
          cancelling.apply(reader.read(shell))
        }
        null
      } catch (failure: kotlinx.coroutines.CancellationException) { failure }
    }
    assertNotNull(failure)
    assertEquals(setOf("one"), contentNames())
    assertEquals(setOf("one", "two"), apply(ManagedRootsAdapter(project, { true })).owned)
  }

  fun testCancelledCommittedCreationKeepsEvidenceForFreshAdapter() {
    val cancelling = ManagedRootsAdapter(project, { true }, verifySnapshot = { snapshot ->
      reader.verifyCurrent(snapshot)
      if (WorkspaceModel.getInstance(project).currentSnapshot.resolve(ModuleId(moduleName)) != null) {
        throw kotlinx.coroutines.CancellationException("Cancel after model commit")
      }
    })
    val failure = awaitUpdate { try { cancelling.apply(reader.read(shell)); null } catch (failure: kotlinx.coroutines.CancellationException) { failure } }
    assertNotNull(failure)
    assertEquals(setOf("one", "two"), contentNames())
    assertEquals(setOf("one", "two"), apply(ManagedRootsAdapter(project, { true })).owned)
  }

  fun testCancelledCreationEvidenceCannotAuthorizeChangedJournal() {
    cancelPreparedAddition()
    val storage = rootsJournalFile(shell)
    val journal = storage.read()!!
    storage.writeAndVerify(journal.copy(pendingAdds = journal.pendingAdds.map { it.copy(nonce = "a".repeat(32)) }.take(1)))
    expectConflict(ManagedRootsAdapter(project, { true }))
    assertNull(WorkspaceModel.getInstance(project).currentSnapshot.resolve(ModuleId(moduleName)))
  }

  fun testCancelledCreationCannotRecoverAfterProjectReopenWithoutProcessEvidence() {
    cancelPreparedAddition()
    PlatformTestUtil.saveProject(project, true)
    val projectLocation = Path.of(project.presentableUrl!!)
    PlatformTestUtil.forceCloseProjectWithoutSaving(project)
    myProject = PlatformTestUtil.loadAndOpenProject(projectLocation, testRootDisposable)
    myModule = com.intellij.openapi.module.ModuleManager.getInstance(project).modules.first()
    expectConflict(ManagedRootsAdapter(project, { true }))
    assertNull(WorkspaceModel.getInstance(project).currentSnapshot.resolve(ModuleId(moduleName)))
  }

  private fun cancelPreparedAddition(insideUpdater: Boolean = false) {
    var updaterChecks = 0
    val cancelling = ManagedRootsAdapter(project, { true }, verifySnapshot = { snapshot ->
      reader.verifyCurrent(snapshot)
      if (rootsJournalFile(shell).read()?.pendingAdds?.any { it.relativePath == "two" } == true) {
        val inMutation = project.getService(com.reqws.goland.projectmodel.ReqwsProjectModelMutationGuard::class.java).isActive
        if (!insideUpdater || inMutation && ++updaterChecks == 2) {
          throw kotlinx.coroutines.CancellationException("Cancel before model commit")
        }
      }
    })
    val failure = awaitUpdate { try { cancelling.apply(reader.read(shell)); null } catch (failure: kotlinx.coroutines.CancellationException) { failure } }
    assertNotNull(failure)
    assertTrue(rootsJournalFile(shell).read()!!.pendingAdds.any { it.relativePath == "two" })
  }

  fun testMetadataReplacementAfterIntentCannotMutateModelOrReplacementDirectory() {
    apply(ManagedRootsAdapter(project, { true }))
    select()
    var replaced = false
    val adapter = ManagedRootsAdapter(project, { true }, verifySnapshot = { snapshot ->
      reader.verifyCurrent(snapshot)
      if (!replaced && rootsJournalFile(shell).read()!!.pendingRemoves.isNotEmpty()) {
        replaced = true
        Files.move(shell.resolve(".idea"), shell.resolve("preserved-idea"))
        Files.createDirectory(shell.resolve(".idea"))
        Files.writeString(shell.resolve(".idea/user.txt"), "replacement metadata")
      }
    })
    expectConflict(adapter)
    assertTrue(replaced)
    assertEquals(setOf("one", "two"), contentNames())
    assertFalse(Files.exists(shell.resolve(".idea/reqws")))
    assertFalse(Files.exists(shell.resolve(".idea/reqws-loaded-roots.json")))
    assertEquals("replacement metadata", Files.readString(shell.resolve(".idea/user.txt")))
  }

  fun testPreparedIntentWithMissingModelCannotReclaimPaths() {
    val adapter = ManagedRootsAdapter(project, { true })
    apply(adapter)
    update { storage -> storage.removeEntity(storage.resolve(ModuleId(moduleName))!!) }
    expectConflict(ManagedRootsAdapter(project, { true }))
    assertTrue(Files.exists(root.resolve("one/probe.txt")))
  }

  private fun select(vararg names: String) {
    val value = JsonObject().apply {
      addProperty("schemaVersion", 1); addProperty("adapterProtocol", 1); addProperty("workspaceId", "ws_1")
      addProperty("bindingId", bindingId); addProperty("revision", 1); addProperty("updatedAt", "2026-09-19T00:00:00Z")
      add("selection", JsonObject().apply { addProperty("mode", "selected"); add("repositoryIds", JsonArray().apply { names.forEach(::add) }) })
    }
    Files.writeString(shell.resolve("reqws-project.json"), value.toString())
  }

  private fun apply(adapter: ManagedRootsAdapter) = awaitUpdate { adapter.apply(reader.read(shell)) }
  private fun contentNames(): Set<String> = WorkspaceModel.getInstance(project).currentSnapshot.resolve(ModuleId(moduleName))!!.contentRoots.mapTo(linkedSetOf()) { it.url.url.substringAfterLast('/') }
  private fun addRoot(name: String) {
    val model = WorkspaceModel.getInstance(project)
    update { storage ->
      val managed = storage.resolve(ModuleId(moduleName))!!
      storage.modifyModuleEntity(managed) { contentRoots += ContentRootEntity(model.getVirtualFileUrlManager().fromPath(root.resolve(name).toString()), emptyList(), managed.entitySource) }
    }
  }
  private fun update(block: (com.intellij.platform.workspace.storage.MutableEntityStorage) -> Unit) = awaitUpdate { WorkspaceModel.getInstance(project).update("Fixture user edit", block) }
  private fun expectConflict(adapter: ManagedRootsAdapter) {
    val failure = awaitUpdate { try { adapter.apply(reader.read(shell)); null } catch (failure: ProjectModelApplyException) { failure } }
    assertNotNull(failure)
    assertEquals(ProjectModelErrorCode.OWNERSHIP_CONFLICT, failure!!.code)
  }
  private fun <T> awaitUpdate(block: suspend () -> T): T = PlatformTestUtil.waitForFuture(AppExecutorUtil.getAppExecutorService().submit(Callable { runBlocking { block() } }))
}
