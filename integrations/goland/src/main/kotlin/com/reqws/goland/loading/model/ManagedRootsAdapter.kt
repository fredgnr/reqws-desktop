package com.reqws.goland.loading.model

import com.intellij.openapi.application.readAction
import com.intellij.openapi.module.ModuleUtilCore
import com.intellij.openapi.module.ModuleManager
import com.intellij.openapi.module.ModuleTypeManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.platform.backend.workspace.WorkspaceModel
import com.intellij.platform.workspace.jps.entities.ContentRootEntity
import com.intellij.platform.workspace.jps.entities.ExcludeUrlEntity
import com.intellij.platform.workspace.jps.entities.ModuleEntity
import com.intellij.platform.workspace.jps.entities.ModuleId
import com.intellij.platform.workspace.jps.entities.ModuleTypeId
import com.intellij.platform.workspace.jps.entities.modifyModuleEntity
import com.intellij.platform.workspace.storage.EntityStorage
import com.intellij.platform.workspace.storage.MutableEntityStorage
import com.intellij.platform.workspace.storage.WorkspaceEntity
import com.intellij.workspaceModel.ide.legacyBridge.LegacyBridgeJpsEntitySourceFactory
import com.reqws.goland.loading.contract.BoundDirectory
import com.reqws.goland.loading.contract.LoadingSnapshot
import com.reqws.goland.loading.contract.LoadingSnapshotReader
import com.reqws.goland.loading.contract.boundDirectory
import com.reqws.goland.manifest.RepositoryAvailability
import com.reqws.goland.projectmodel.ProjectModelApplyException
import com.reqws.goland.projectmodel.ProjectModelErrorCode
import com.reqws.goland.projectmodel.ReqwsProjectModelMutationGuard
import com.intellij.openapi.components.service
import com.intellij.openapi.progress.ProcessCanceledException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.delay
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.util.UUID

internal const val ROOT_MARKER_NAMESPACE = ".reqws-goland-ownership"

internal data class ManagedRootsResult(val owned: Set<String>, val borrowed: Set<String>, val userCoverage: Set<String>)

/** Root-level edits only: native roots and all unrelated user configuration remain untouched. */
internal class ManagedRootsAdapter(
  private val project: Project,
  private val allowed: () -> Boolean,
  private val verifySnapshot: (LoadingSnapshot) -> Unit = LoadingSnapshotReader()::verifyCurrent,
  private val observedMetadata: java.util.concurrent.atomic.AtomicBoolean = java.util.concurrent.atomic.AtomicBoolean(false),
) {

  suspend fun apply(snapshot: LoadingSnapshot): ManagedRootsResult {
    try {
      gate(snapshot)
      val idea = snapshot.binding.shell.resolve(".idea")
      val ideaIdentity = try { boundDirectory(idea) } catch (failure: NoSuchFileException) {
        if (!observedMetadata.get()) throw ProjectModelApplyException(ProjectModelErrorCode.PROJECT_METADATA_NOT_READY, "IDE metadata is not ready.", failure)
        throw failure
      }
      observedMetadata.set(true)
      var moduleDirectoryIdentity: BoundDirectory? = null
      var moduleJournal: LoadedRootsJournal? = null
      fun verifyTransaction() {
        gate(snapshot)
        if (boundDirectory(idea) != ideaIdentity) conflict("IDE metadata directory changed during reconciliation.")
        moduleDirectoryIdentity?.let { expected ->
          if (boundDirectory(expected.path) != expected) conflict("Managed module directory changed during reconciliation.")
        }
        moduleJournal?.let(::verifyModuleFile)
      }
      return rootsJournalFile(snapshot.binding.shell).withStableParentSuspending { storage ->
        val lock = storage.tryAcquireExclusiveDirectoryLock() ?: conflict("Another plugin writer owns the shell.")
        lock.use {
          verifyTransaction()
          val existing = storage.read()?.also { it.verifyBinding(snapshot) }
          val name = "ReqWS-${snapshot.binding.bindingId}"
          val moduleFile = idea.resolve("reqws/$name.iml")
          if (Files.exists(moduleFile.parent, LinkOption.NOFOLLOW_LINKS)) {
            moduleDirectoryIdentity = boundDirectory(moduleFile.parent)
          }
          val journal = existing ?: LoadedRootsJournal(
            snapshot.binding.workspaceId, snapshot.binding.bindingId, snapshot.binding.workspaceRoot.toString(),
            snapshot.binding.shell.toString(), boundDirectory(snapshot.binding.shell).fileKey, ideaIdentity.fileKey,
            name, moduleFile.toString(), emptyList(), emptyList(), emptyList(),
          )
          val model = WorkspaceModel.getInstance(project)
          val before = model.currentSnapshot
          val module = before.resolve(ModuleId(name))
          if (existing != null && module == null) conflict("The managed module is missing; recovery cannot prove it was never saved.")
          if (existing == null && (module != null || Files.exists(moduleFile, LinkOption.NOFOLLOW_LINKS))) conflict("The module name or file belongs to an unknown entry.")
          if (module != null) {
            verifyModuleFile(journal)
            moduleJournal = journal
          }
          // Pending records authorize only reconciliation of exact surviving marker evidence.
          val claims = (journal.claims + journal.pendingAdds + journal.pendingRemoves).distinct()
          val currentClaims = claims.filter { claim ->
            val roots = exactRoots(before, rootUrl(snapshot, claim))
            if (roots.isEmpty()) {
              if (claim !in journal.pendingRemoves) conflict("An owned root is missing; it cannot be reclaimed from a path list.")
              false
            } else {
              verifyClaim(before, snapshot, journal, claim, requireDeletable = false)
              true
            }
          }
          val desired = snapshot.loadedRepositories.filter { it.availability == RepositoryAvailability.PRESENT }
          val desiredPaths = desired.mapTo(linkedSetOf()) { it.repository.relativePath }
          val remove = currentClaims.filter { it.relativePath !in desiredPaths }
          remove.forEach { verifyClaim(before, snapshot, journal, it, requireDeletable = true) }
          val keep = currentClaims.filter { it.relativePath in desiredPaths }
          keep.forEach { claim ->
            if (desired.single { it.repository.relativePath == claim.relativePath }.repository.catalogRepositoryId != claim.repositoryId) conflict("Repository ID changed for an owned path.")
          }
          val borrowed = linkedSetOf<String>()
          val add = desired.filter { repo -> keep.none { it.relativePath == repo.repository.relativePath } }.mapNotNull { repo ->
            val url = model.getVirtualFileUrlManager().fromPath(repo.path.toString()).url
            val roots = exactRoots(before, url)
            if (roots.isNotEmpty()) {
              if (roots.size != 1) conflict("Multiple roots refer to one requested repository.")
              borrowed += repo.repository.catalogRepositoryId
              null
            } else {
              requireVirtualNamespace(repo.path)
              RootClaim(repo.repository.catalogRepositoryId, repo.repository.relativePath,
                UUID.randomUUID().toString().replace("-", ""), boundDirectory(repo.path).fileKey,
                boundDirectory(repo.path.resolve(".git")).fileKey)
            }
          }
          val nextClaims = keep + add
          val nextPaths = nextClaims.mapTo(hashSetOf()) { it.relativePath }
          // Live absence is not evidence that .iml has been saved. Retain recovery claims across
          // repeated same-process reconciliations; a new owned claim for that path supersedes them.
          val recoveryAdds = (journal.pendingAdds + add).distinct().filter { it in nextClaims }
          val recoveryRemoves = (journal.pendingRemoves + remove).distinct().filter { it.relativePath !in nextPaths }
          val prepared = journal.copy(pendingAdds = recoveryAdds, pendingRemoves = recoveryRemoves, claims = currentClaims)
          verifyTransaction()
          storage.writeAndVerify(prepared)
          verifyTransaction()
          // Persist intent before allocating the stable module file directory.
          val moduleDirectory = moduleFile.parent
          if (!Files.exists(moduleDirectory, LinkOption.NOFOLLOW_LINKS)) Files.createDirectory(moduleDirectory)
          val currentModuleDirectory = boundDirectory(moduleDirectory)
          if (moduleDirectoryIdentity != null && moduleDirectoryIdentity != currentModuleDirectory) {
            conflict("Managed module directory changed before model update.")
          }
          moduleDirectoryIdentity = currentModuleDirectory
          verifyTransaction()
          val urls = model.getVirtualFileUrlManager()
          val source = module?.entitySource ?: LegacyBridgeJpsEntitySourceFactory.getInstance(project)
            .createEntitySourceForModule(urls.fromPath(moduleDirectory.toString()), null)
          project.service<ReqwsProjectModelMutationGuard>().withSuspendingMutation {
            model.update("Apply ReqWS loaded repository roots") { builder ->
              verifyTransaction()
              if (builder.resolve(ModuleId(name)) != module) conflict("The managed module changed during planning.")
              val target = module ?: builder.addEntity(ModuleEntity(name, emptyList(), source) {
                type = ModuleTypeId(ModuleTypeManager.getInstance().defaultModuleType.id)
              })
              remove.forEach { claim ->
                val root = verifyClaim(builder, snapshot, journal, claim, requireDeletable = true)
                builder.removeEntity(root)
              }
              add.forEach { claim ->
                verifyFilesystem(snapshot, claim)
                if (exactRoots(builder, rootUrl(snapshot, claim)).isNotEmpty()) conflict("A user root appeared before commit.")
                val root = ContentRootEntity(urls.fromPath(snapshot.binding.workspaceRoot.resolve(claim.relativePath).toString()), emptyList(), source) {
                  excludedUrls = listOf(ExcludeUrlEntity(urls.fromPath(markerPath(snapshot, claim).toString()), source))
                }
                builder.modifyModuleEntity(target) { contentRoots += root }
              }
              verifyTransaction()
            }
          }
          if (module == null) project.service<ModuleCreationEvidence>().created(journal.moduleFile, source)
          moduleJournal = journal
          currentCoroutineContext().ensureActive()
          verifyTransaction()
          val coverage = verifyPfi(snapshot, journal, keep + add, borrowed, ::verifyTransaction)
          verifyTransaction()
          // Keep recovery evidence conservatively: neither an API return nor live absence proves
          // durable .iml persistence. A later owned claim can explicitly supersede a removed path.
          storage.writeAndVerify(prepared.copy(claims = nextClaims))
          verifyTransaction()
          ManagedRootsResult((keep + add).mapTo(linkedSetOf()) { it.repositoryId }, borrowed, coverage)
        }
      }
    } catch (failure: CancellationException) { throw failure }
    catch (failure: ProcessCanceledException) { throw failure }
    catch (failure: ProjectModelApplyException) { throw failure }
    catch (failure: Exception) { throw ProjectModelApplyException(ProjectModelErrorCode.OWNERSHIP_CONFLICT, "Unable to safely reconcile loaded roots.", failure) }
  }

  private fun gate(snapshot: LoadingSnapshot) {
    if (project.isDisposed || !allowed()) throw CancellationException("Loading candidate is no longer current or trusted.")
    verifySnapshot(snapshot)
  }

  private fun verifyModuleFile(journal: LoadedRootsJournal) {
    val module = ModuleManager.getInstance(project).findModuleByName(journal.moduleName) ?: conflict("Missing module bridge.")
    val file = Path.of(journal.moduleFile)
    boundDirectory(file.parent)
    val exists = Files.exists(file, LinkOption.NOFOLLOW_LINKS)
    if (exists && (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(file) || file.toRealPath() != file)) conflict("Managed module file is unsafe.")
    val moduleDirectory = ModuleUtilCore.getModuleDirPath(module)
    if (Path.of(moduleDirectory).toAbsolutePath().normalize() != file.parent) conflict("Managed module directory changed.")
    val evidence = project.service<ModuleCreationEvidence>()
    if (exists) {
      val moduleFile = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(file)
        ?: conflict("The managed module file is missing from VFS.")
      if (!ModuleUtilCore.isModuleFile(module, moduleFile)) conflict("Managed module file changed.")
      evidence.observedSaved(journal.moduleFile)
    } else {
      val entity = WorkspaceModel.getInstance(project).currentSnapshot.resolve(ModuleId(journal.moduleName))!!
      if (!evidence.isFreshUnsaved(journal.moduleFile, entity.entitySource)) conflict("The exact managed module file is missing.")
    }
  }

  private fun verifyFilesystem(snapshot: LoadingSnapshot, claim: RootClaim) {
    val root = snapshot.binding.workspaceRoot.resolve(claim.relativePath)
    if (boundDirectory(root).fileKey != claim.rootKey || boundDirectory(root.resolve(".git")).fileKey != claim.gitKey) conflict("Repository identity changed.")
    requireVirtualNamespace(root)
  }

  private fun requireVirtualNamespace(root: Path) {
    if (Files.exists(root.resolve(ROOT_MARKER_NAMESPACE), LinkOption.NOFOLLOW_LINKS)) conflict("The marker namespace exists on disk.")
  }

  private fun verifyClaim(storage: EntityStorage, snapshot: LoadingSnapshot, journal: LoadedRootsJournal, claim: RootClaim, requireDeletable: Boolean): ContentRootEntity {
    verifyFilesystem(snapshot, claim)
    val root = exactRoots(storage, rootUrl(snapshot, claim)).singleOrNull() ?: conflict("Owned root is missing or ambiguous.")
    if (root.module.name != journal.moduleName) conflict("Owned root moved to another module.")
    val markerUrl = WorkspaceModel.getInstance(project).getVirtualFileUrlManager().fromPath(markerPath(snapshot, claim).toString()).url
    val marker = storage.entities(ExcludeUrlEntity::class.java).filter { it.url.url == markerUrl }.singleOrNull() ?: conflict("Ownership marker is missing or duplicated.")
    if (marker !in root.excludedUrls || marker.entitySource != root.entitySource || root.entitySource != root.module.entitySource) conflict("Marker source does not belong to its root.")
    if (requireDeletable) {
      if (root.sourceRoots.isNotEmpty() || root.excludedPatterns.isNotEmpty() || root.excludedUrls != listOf(marker)) conflict("Owned root contains user configuration.")
      // Preview the cascading deletion using public storage APIs. Reject unknown child relations.
      val preview = MutableEntityStorage.from(when (storage) {
        is MutableEntityStorage -> storage.toSnapshot()
        else -> WorkspaceModel.getInstance(project).currentSnapshot
      })
      val previewRoot = exactRoots(preview, root.url.url).single()
      preview.removeEntity(previewRoot)
      val after = preview.toSnapshot()
      val removed = storage.entitiesBySource { true }.filter { it.createPointer<WorkspaceEntity>().resolve(after) == null }.toList()
      if (removed.any { entity -> entity != root && entity != marker }) {
        conflict("Deletion would remove unsupported child configuration.")
      }
    }
    return root
  }

  private suspend fun verifyPfi(snapshot: LoadingSnapshot, journal: LoadedRootsJournal, claims: List<RootClaim>, borrowed: Set<String>, verifyCurrent: () -> Unit): Set<String> {
    val fs = LocalFileSystem.getInstance()
    val present = snapshot.manifest.repositories.filter { it.availability == RepositoryAvailability.PRESENT }
      .associateWith { fs.refreshAndFindFileByNioFile(it.path) ?: conflict("A repository disappeared from VFS.") }
    repeat(20) { attempt ->
      currentCoroutineContext().ensureActive()
      verifyCurrent()
      val result = readAction {
        val index = ProjectFileIndex.getInstance(project)
        val mismatch = present.any { (repo, file) ->
          repo.repository.catalogRepositoryId in snapshot.loadedIds && (
            !index.isInContent(file) || index.isExcluded(file) || index.getContentRootForFile(file) != file ||
              repo.repository.catalogRepositoryId !in borrowed && index.getModuleForFile(file)?.name != journal.moduleName)
        }
        val coverage = present.filter { (repo, file) -> repo.repository.catalogRepositoryId !in snapshot.loadedIds && index.isInContent(file) }
          .keys.mapTo(linkedSetOf()) { it.repository.catalogRepositoryId }
        mismatch to coverage
      }
      if (!result.first) {
        val live = WorkspaceModel.getInstance(project).currentSnapshot
        claims.forEach { verifyClaim(live, snapshot, journal, it, false) }
        return result.second
      }
      if (attempt < 19) delay(50)
    }
    throw ProjectModelApplyException(ProjectModelErrorCode.LIVE_FILE_INDEX_NOT_CONVERGED, "Loaded repository boundaries did not converge.")
  }

  private fun exactRoots(storage: EntityStorage, url: String) = storage.entities(ContentRootEntity::class.java).filter { it.url.url == url }.toList()
  private fun rootUrl(snapshot: LoadingSnapshot, claim: RootClaim) = WorkspaceModel.getInstance(project).getVirtualFileUrlManager().fromPath(snapshot.binding.workspaceRoot.resolve(claim.relativePath).toString()).url
  private fun markerPath(snapshot: LoadingSnapshot, claim: RootClaim) = snapshot.binding.workspaceRoot.resolve(claim.relativePath).resolve(ROOT_MARKER_NAMESPACE).resolve(claim.nonce)
  private fun conflict(message: String): Nothing = throw ProjectModelApplyException(ProjectModelErrorCode.OWNERSHIP_CONFLICT, message)
}

/** Only the project instance that created an as-yet-unsaved module may rely on its live source. */
@com.intellij.openapi.components.Service(com.intellij.openapi.components.Service.Level.PROJECT)
internal class ModuleCreationEvidence {
  private val sources = java.util.concurrent.ConcurrentHashMap<String, com.intellij.platform.workspace.storage.EntitySource>()
  private val saved = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
  fun created(file: String, source: com.intellij.platform.workspace.storage.EntitySource) { sources[file] = source }
  fun observedSaved(file: String) { saved += file }
  fun isFreshUnsaved(file: String, source: com.intellij.platform.workspace.storage.EntitySource): Boolean = file !in saved && sources[file] == source
}
