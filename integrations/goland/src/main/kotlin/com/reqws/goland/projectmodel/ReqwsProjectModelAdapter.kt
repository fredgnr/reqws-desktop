package com.reqws.goland.projectmodel

import com.intellij.ide.trustedProjects.TrustedProjects
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.platform.backend.workspace.WorkspaceModel
import com.intellij.platform.workspace.jps.entities.ExcludeUrlEntity
import com.intellij.platform.workspace.jps.entities.ModuleEntity
import com.intellij.platform.workspace.jps.entities.ModuleId
import com.intellij.platform.workspace.jps.entities.modifyContentRootEntity
import com.intellij.platform.workspace.storage.url.VirtualFileUrl
import com.reqws.goland.manifest.ManifestSnapshot
import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.LinkOption
import java.nio.file.NoSuchFileException
import java.nio.file.NotDirectoryException
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes
import java.security.SecureRandom

private const val REQWS_METADATA_DIRECTORY = ".reqws"
private const val REQWS_MARKER_DIRECTORY = ".goland-ownership"
private val MARKER_TOKEN_PATTERN = Regex("^[0-9a-f]{32}$")
private val markerRandom = SecureRandom()

private fun newMarkerToken(): String = ByteArray(16)
  .also(markerRandom::nextBytes)
  .joinToString(separator = "") { byte -> "%02x".format(byte.toInt() and 0xff) }

enum class ProjectModelErrorCode {
  PROJECT_DISPOSED,
  UNTRUSTED_PROJECT,
  INVALID_OWNERSHIP_STATE,
  AMBIGUOUS_WORKSPACE_ROOT,
  NESTED_CONTENT_ROOT_CONFLICT,
  OWNERSHIP_CONFLICT,
  RETAINED_REPOSITORY_DISCOVERY_FAILED,
}

class ProjectModelApplyException(
  val code: ProjectModelErrorCode,
  message: String,
  cause: Throwable? = null,
) : IllegalStateException(message, cause)

data class ProjectModelApplyResult(
  val strategy: String,
  val moduleName: String,
  val managedExcludes: Set<String>,
  val added: Set<String>,
  val removed: Set<String>,
  val kept: Set<String>,
  val borrowed: Set<String>,
  val staleOwnership: Set<String>,
)

@Service(Service.Level.PROJECT)
class ReqwsProjectModelAdapter(
  private val project: Project,
) {
  suspend fun apply(
    snapshot: ManifestSnapshot,
    isServiceDisposed: () -> Boolean = { false },
  ): ProjectModelApplyResult =
    WorkspaceExcludeModelAdapter(
      project = project,
      ownershipState = project.service(),
      isTrusted = { TrustedProjects.isProjectTrusted(project) },
      isProjectDisposed = { project.isDisposed || isServiceDisposed() },
    ).apply(snapshot)
}

internal class WorkspaceExcludeModelAdapter(
  private val project: Project,
  private val ownershipState: ReqwsManagedModelState,
  private val isTrusted: () -> Boolean,
  private val isProjectDisposed: () -> Boolean = { project.isDisposed },
  private val markerTokenFactory: () -> String = ::newMarkerToken,
  private val persistPendingState: () -> Unit = project::save,
  private val pathsReferToSameFile: (Path, Path) -> Boolean = { first, second ->
    Files.isSameFile(first, second)
  },
) {
  suspend fun apply(snapshot: ManifestSnapshot): ProjectModelApplyResult {
    ensureMutationAllowed()

    val ownership = validatedOwnership(snapshot.canonicalProjectRoot)
    ensureVirtualMarkerNamespace(snapshot.canonicalProjectRoot)
    val currentActivePaths = currentActiveRepositoryPaths(snapshot)
    val desiredRelativePaths = desiredExcludedPaths(snapshot, currentActivePaths.values)
    val workspaceModel = WorkspaceModel.getInstance(project)
    val urlManager = workspaceModel.getVirtualFileUrlManager()
    val workspaceUrl = urlManager.fromPath(snapshot.canonicalProjectRoot.toString())
    val plannedModelSnapshot = workspaceModel.currentSnapshot
    val target = selectTarget(plannedModelSnapshot, workspaceUrl, ownership.targetModuleName)
    val desiredUrls = desiredRelativePaths.associateWith { relative ->
      urlManager.fromPath(resolveRelative(snapshot.canonicalProjectRoot, relative).toString())
    }
    val activeUrls = snapshot.repositories.associate { resolved ->
      val activePath = currentActivePaths[resolved.repository.relativePath]
        ?: resolveRelative(snapshot.canonicalProjectRoot, resolved.repository.relativePath)
      resolved.repository.relativePath to urlManager.fromPath(activePath.toString())
    }
    fun claimsFor(tokens: Map<String, String>): Map<String, ManagedExcludeClaim> =
      tokens.mapValues { (relative, markerToken) ->
        ManagedExcludeClaim(
          targetUrl = urlManager.fromPath(
            resolveRelative(snapshot.canonicalProjectRoot, relative).toString(),
          ).url,
          markerToken = markerToken,
          markerUrl = urlManager.fromPath(
            markerPath(snapshot.canonicalProjectRoot, markerToken).toString(),
          ).url,
        )
      }
    val previousClaims = claimsFor(ownership.managedExcludes)
    val pendingAddClaims = claimsFor(ownership.pendingAdds)
    val pendingRemoveClaims = claimsFor(ownership.pendingRemovals)
    val persistedTokens = ownership.managedExcludes.values +
      ownership.pendingAdds.values +
      ownership.pendingRemovals.values
    val candidateTokens = candidateMarkerTokens(
      desiredRelativePaths - ownership.managedExcludes.keys - ownership.pendingAdds.keys,
      persistedTokens.toSet(),
    )
    val markerUrlsByToken = (persistedTokens + candidateTokens.values)
      .associateWith { markerToken ->
        urlManager.fromPath(markerPath(snapshot.canonicalProjectRoot, markerToken).toString())
      }
    val candidateMarkerUrls = candidateTokens.mapValues { (_, markerToken) ->
      markerUrlsByToken.getValue(markerToken)
    }
    val candidateClaims = candidateTokens.mapValues { (relative, markerToken) ->
      ManagedExcludeClaim(
        targetUrl = desiredUrls.getValue(relative).url,
        markerToken = markerToken,
        markerUrl = candidateMarkerUrls.getValue(relative).url,
      )
    }
    val ownershipProofUrls = buildList {
      addAll(desiredUrls.values)
      addAll(markerUrlsByToken.values)
    }
    ensureNoNestedContentRootConflict(plannedModelSnapshot, target, ownershipProofUrls)
    val plannedModule = plannedModelSnapshot.resolve(ModuleId(target.moduleName))
      ?: throw ownershipConflict("The target module disappeared during planning.")
    val plannedContentRoot = plannedModule.contentRoots.singleOrNull { it.url.url == target.url.url }
      ?: throw ownershipConflict("The target workspace Content Root changed during planning.")
    val plannedExcludedUrls = plannedContentRoot.excludedUrls.map { entity -> entity.url.url }
    val plan = ReqwsExcludePlanner.plan(
      desiredUrls = desiredUrls.mapValues { it.value.url },
      activeUrls = activeUrls.values.mapTo(mutableSetOf()) { it.url },
      previousClaims = previousClaims,
      pendingAddClaims = pendingAddClaims,
      pendingRemoveClaims = pendingRemoveClaims,
      candidateClaims = candidateClaims,
      currentExcludes = plannedExcludedUrls.map(::CurrentExclude),
      urlsEquivalent = ::urlsReferToSameFile,
    )
    val changesModel = plan.added.isNotEmpty() || plan.removed.isNotEmpty()
    val requiresPendingModelBarrier = changesModel ||
      ownership.pendingAdds.isNotEmpty() ||
      ownership.pendingRemovals.isNotEmpty()
    val expectedExcludedUrls = plannedExcludedUrls.filter { it !in plan.removableUrls } +
      plan.added.flatMap { relative ->
        val claim = plan.addedClaims.getValue(relative)
        listOf(
          desiredUrls.getValue(relative).url,
          markerUrlsByToken.getValue(claim.markerToken).url,
        )
      }
    val finalOwnershipNeedsPersistence =
      ownership.stateVersion != REQWS_MODEL_STATE_VERSION ||
        ownership.targetModuleName != target.moduleName ||
        ownership.managedExcludes != plan.nextOwnership ||
        ownership.pendingAdds.isNotEmpty() ||
        ownership.pendingRemovals.isNotEmpty()

    if (changesModel) {
      ensureMutationAllowed()
      ownershipState.replaceOwnership(
        target.moduleName,
        plan.preparedOwnership,
        plan.preparedPendingAdds,
        plan.preparedPendingRemovals,
      )
      // The journal must reach the project store before a Workspace Model mutation can commit.
      // A crash after this save observes either the old model plus pending intent, or the new model
      // plus the same pending intent; both cases are recoverable on the next cold apply.
      ensureMutationAllowed()
      persistProjectState("Unable to persist the ReqWS project model transition journal.")
      ensureMutationAllowed()
    }

    ensureMutationAllowed()
    workspaceModel.update("Synchronize ReqWS project excludes") { storage ->
      ensureMutationAllowed()
      val module = storage.resolve(ModuleId(target.moduleName))
        ?: throw ownershipConflict("The target module disappeared during synchronization.")
      val contentRoot = module.contentRoots.singleOrNull { it.url.url == target.url.url }
        ?: throw ownershipConflict("The target workspace Content Root changed during synchronization.")
      ensureNoNestedContentRootConflict(storage, target, ownershipProofUrls)
      if (contentRoot.excludedUrls.map { entity -> entity.url.url } != plannedExcludedUrls) {
        throw ownershipConflict(
          "The target workspace excludes changed after the ReqWS transition was persisted.",
        )
      }

      storage.modifyContentRootEntity(contentRoot) {
        val keptEntities = excludedUrls.filter { it.url.url !in plan.removableUrls }
        val desiredEntities = plan.added.flatMap { relative ->
          val claim = plan.addedClaims.getValue(relative)
          listOf(
            ExcludeUrlEntity(desiredUrls.getValue(relative), contentRoot.entitySource),
            ExcludeUrlEntity(
              markerUrlsByToken.getValue(claim.markerToken),
              contentRoot.entitySource,
            ),
          )
        }
        // Keep unrelated user entries byte-for-byte, including duplicates. The planner has
        // already rejected duplicates for URLs relevant to this ReqWS transition.
        ensureMutationAllowed()
        excludedUrls = keptEntities + desiredEntities
        // Throwing from this transaction still rolls back the just-assigned model. Recheck after
        // the assignment so a trust/dispose transition at the commit boundary cannot publish it.
        ensureMutationAllowed()
      }
      ensureMutationAllowed()
    }

    if (requiresPendingModelBarrier) {
      // Keep the journal pending until a second save has persisted the committed Workspace Model.
      // This also covers a same-process retry that sees an already committed pending transition:
      // its model may still be newer than disk even though this retry itself made no model change.
      ensureMutationAllowed()
      persistProjectState(
        "Unable to persist the committed ReqWS project model with its transition journal.",
      )
      ensureMutationAllowed()
      requireExpectedModel(
        workspaceModel = workspaceModel,
        target = target,
        ownershipProofUrls = ownershipProofUrls,
        expectedExcludedUrls = expectedExcludedUrls,
        changedMessage = "The target workspace excludes changed while the ReqWS transition was saved.",
      )
    }

    val applied = TransactionOutcome(
      ownership = plan.nextOwnership,
      preparedOwnership = plan.preparedOwnership,
      preparedPendingAdds = plan.preparedPendingAdds,
      preparedPendingRemovals = plan.preparedPendingRemovals,
      added = plan.added,
      removed = plan.removed,
      kept = plan.kept,
      borrowed = plan.borrowed,
      stale = plan.staleOwnership,
    )
    // Workspace Model and PersistentStateComponent do not share one transaction. The state was
    // moved to recoverable pending phases before the model mutation, so a gate failure here leaves
    // enough intent to finish or undo that exact target+marker pair on a later trusted sync.
    ensureMutationAllowed()
    ownershipState.replaceOwnership(target.moduleName, applied.ownership)
    try {
      ensureMutationAllowed()
      if (finalOwnershipNeedsPersistence) {
        persistProjectState("Unable to persist the final ReqWS project model ownership.")
        ensureMutationAllowed()
        if (requiresPendingModelBarrier) {
          requireExpectedModel(
            workspaceModel = workspaceModel,
            target = target,
            ownershipProofUrls = ownershipProofUrls,
            expectedExcludedUrls = expectedExcludedUrls,
            changedMessage = "The target workspace excludes changed while ReqWS ownership was finalized.",
          )
        }
      }
    } catch (exception: ProjectModelApplyException) {
      ownershipState.replaceOwnership(
        target.moduleName,
        applied.preparedOwnership,
        applied.preparedPendingAdds,
        applied.preparedPendingRemovals,
      )
      throw exception
    }
    return ProjectModelApplyResult(
      strategy = REQWS_MODEL_STRATEGY,
      moduleName = target.moduleName,
      managedExcludes = applied.ownership.keys,
      added = applied.added,
      removed = applied.removed,
      kept = applied.kept,
      borrowed = applied.borrowed,
      staleOwnership = applied.stale,
    )
  }

  private fun validatedOwnership(workspaceRoot: Path): ValidatedOwnership {
    val ownership = ownershipState.ownership()
    val allPersistedClaims = ownership.managedExcludes +
      ownership.pendingAdds +
      ownership.pendingRemovals
    if (
      ownership.stateVersion !in setOf(
        REQWS_LEGACY_MODEL_STATE_VERSION,
        REQWS_MODEL_STATE_VERSION,
      ) ||
      ownership.strategy != REQWS_MODEL_STRATEGY ||
      (allPersistedClaims.isNotEmpty() && ownership.targetModuleName.isEmpty()) ||
      (
        ownership.stateVersion == REQWS_LEGACY_MODEL_STATE_VERSION &&
          (ownership.pendingAdds.isNotEmpty() || ownership.pendingRemovals.isNotEmpty())
      )
    ) {
      throw ProjectModelApplyException(
        ProjectModelErrorCode.INVALID_OWNERSHIP_STATE,
        "Unsupported ReqWS project model ownership state.",
      )
    }
    val allRelativePaths = hashSetOf<String>()
    val markerTokens = hashSetOf<String>()
    fun validatedClaims(claims: List<ManagedExcludeOwnership>): Map<String, String> {
      val validated = linkedMapOf<String, String>()
      claims.forEach { claim ->
        resolveRelative(workspaceRoot, claim.relativePath)
        if (
          !MARKER_TOKEN_PATTERN.matches(claim.markerToken) ||
          !allRelativePaths.add(claim.relativePath) ||
          !markerTokens.add(claim.markerToken)
        ) {
          throw invalidState("Invalid or duplicate ReqWS project model ownership proof.")
        }
        validated[claim.relativePath] = claim.markerToken
      }
      return validated
    }
    return ValidatedOwnership(
      stateVersion = ownership.stateVersion,
      targetModuleName = ownership.targetModuleName,
      managedExcludes = validatedClaims(ownership.managedExcludes),
      pendingAdds = validatedClaims(ownership.pendingAdds),
      pendingRemovals = validatedClaims(ownership.pendingRemovals),
    )
  }

  private fun candidateMarkerTokens(
    relativePaths: Set<String>,
    previousTokens: Set<String>,
  ): Map<String, String> {
    val usedTokens = previousTokens.toMutableSet()
    return relativePaths.sorted().associateWith {
      repeat(64) {
        val candidate = markerTokenFactory()
        if (MARKER_TOKEN_PATTERN.matches(candidate) && usedTokens.add(candidate)) {
          return@associateWith candidate
        }
      }
      throw ownershipConflict("Unable to allocate a unique ReqWS ownership marker.")
    }
  }

  private fun markerPath(workspaceRoot: Path, markerToken: String): Path = workspaceRoot
    .resolve(REQWS_METADATA_DIRECTORY)
    .resolve(REQWS_MARKER_DIRECTORY)
    .resolve(markerToken)

  private fun ensureVirtualMarkerNamespace(workspaceRoot: Path) {
    val metadataDirectory = workspaceRoot.resolve(REQWS_METADATA_DIRECTORY)
    val markerDirectory = metadataDirectory.resolve(REQWS_MARKER_DIRECTORY)
    if (
      !Files.isDirectory(metadataDirectory, LinkOption.NOFOLLOW_LINKS) ||
      Files.exists(markerDirectory, LinkOption.NOFOLLOW_LINKS)
    ) {
      throw ownershipConflict(
        "The ReqWS ownership marker namespace must remain virtual and symlink-free.",
      )
    }
  }

  private fun currentActiveRepositoryPaths(snapshot: ManifestSnapshot): Map<String, Path> {
    val current = linkedMapOf<String, Path>()
    val identities = hashSetOf<Path>()
    snapshot.repositories.forEachIndexed { index, resolved ->
      val candidate = resolveRelative(
        snapshot.canonicalProjectRoot,
        resolved.repository.relativePath,
      )
      if (!Files.exists(candidate, LinkOption.NOFOLLOW_LINKS)) return@forEachIndexed
      val canonical = try {
        candidate.toRealPath()
      } catch (exception: Exception) {
        throw activeIdentityFailure(exception)
      }
      if (
        canonical == snapshot.canonicalProjectRoot ||
        !canonical.startsWith(snapshot.canonicalProjectRoot) ||
        !Files.isDirectory(canonical)
      ) {
        throw activeIdentityFailure()
      }
      if (!identities.add(canonical)) {
        throw ProjectModelApplyException(
          ProjectModelErrorCode.OWNERSHIP_CONFLICT,
          "Two active repositories resolve to one filesystem identity at index $index.",
        )
      }
      current[resolved.repository.relativePath] = canonical
    }
    return current
  }

  private fun desiredExcludedPaths(
    snapshot: ManifestSnapshot,
    activePaths: Collection<Path>,
  ): Set<String> {
    val desired = mutableSetOf(REQWS_METADATA_DIRECTORY)
    try {
      Files.list(snapshot.canonicalProjectRoot).use { children ->
        children
          .filter { child -> Files.isDirectory(child, LinkOption.NOFOLLOW_LINKS) }
          .filter { child -> activePaths.none { active -> Files.isSameFile(child, active) } }
          .filter { child -> child.fileName.toString() != REQWS_METADATA_DIRECTORY }
          .filter(::hasGitMarker)
          .forEach { child -> desired.add(child.fileName.toString()) }
      }
    } catch (exception: Exception) {
      throw ProjectModelApplyException(
        ProjectModelErrorCode.RETAINED_REPOSITORY_DISCOVERY_FAILED,
        "Unable to inspect retained repositories for the ReqWS project model.",
        exception,
      )
    }
    return desired
  }

  private fun activeIdentityFailure(cause: Throwable? = null) =
    ProjectModelApplyException(
      ProjectModelErrorCode.RETAINED_REPOSITORY_DISCOVERY_FAILED,
      "Unable to verify an active repository filesystem identity.",
      cause,
    )

  private fun hasGitMarker(directory: Path): Boolean =
    Files.isDirectory(directory.resolve(".git"), LinkOption.NOFOLLOW_LINKS)

  private fun resolveRelative(workspaceRoot: Path, relative: String): Path {
    val relativePath = try {
      Path.of(relative)
    } catch (exception: InvalidPathException) {
      throw invalidState("Invalid managed relative path.", exception)
    }
    if (
      relative.isEmpty() ||
      relativePath.isAbsolute ||
      relativePath.nameCount != 1 ||
      relativePath.normalize() != relativePath
    ) {
      throw invalidState("Invalid managed relative path.")
    }
    val root = workspaceRoot.toAbsolutePath().normalize()
    val resolved = root.resolve(relativePath).normalize()
    if (!resolved.startsWith(root)) {
      throw invalidState("Managed path escapes the workspace root.")
    }
    if (Files.exists(resolved, LinkOption.NOFOLLOW_LINKS)) {
      try {
        if (!resolved.toRealPath().startsWith(root.toRealPath())) {
          throw invalidState("Managed path escapes the workspace root through a symlink.")
        }
      } catch (exception: ProjectModelApplyException) {
        throw exception
      } catch (exception: Exception) {
        throw invalidState("Unable to verify a managed path.", exception)
      }
    }
    return resolved
  }

  private fun selectTarget(
    entityStorage: com.intellij.platform.workspace.storage.EntityStorage,
    workspaceUrl: VirtualFileUrl,
    previousModuleName: String,
  ): TargetRoot {
    val candidates = entityStorage
      .entities(ModuleEntity::class.java)
      .flatMap { module ->
        module.contentRoots.asSequence()
          .filter { urlsReferToSameFile(it.url.url, workspaceUrl.url) }
          .map { TargetRoot(module.name, it.url) }
      }
      .toList()
    if (candidates.size != 1) {
      throw ProjectModelApplyException(
        ProjectModelErrorCode.AMBIGUOUS_WORKSPACE_ROOT,
        "ReqWS requires exactly one existing Content Root for the workspace root.",
      )
    }
    val target = candidates.single()
    if (previousModuleName.isNotEmpty() && previousModuleName != target.moduleName) {
      throw ownershipConflict("The previously managed module no longer owns the workspace root.")
    }
    return target
  }

  private fun ensureNoNestedContentRootConflict(
    entityStorage: com.intellij.platform.workspace.storage.EntityStorage,
    target: TargetRoot,
    desiredUrls: Collection<VirtualFileUrl>,
  ) {
    val desiredUrlStrings = desiredUrls.map { it.url }
    val conflicts = entityStorage
      .entities(ModuleEntity::class.java)
      .flatMap { module ->
        module.contentRoots.asSequence().map { contentRoot -> module.name to contentRoot.url.url }
      }
      .any { (moduleName, url) ->
        (moduleName != target.moduleName || url != target.url.url) &&
          desiredUrlStrings.any { desiredUrl -> urlsReferToSameFile(url, desiredUrl) }
      }
    if (conflicts) {
      throw ProjectModelApplyException(
        ProjectModelErrorCode.NESTED_CONTENT_ROOT_CONFLICT,
        "A ReqWS exclude target is also configured as a Content Root.",
      )
    }
  }

  private fun urlsReferToSameFile(firstUrl: String, secondUrl: String): Boolean {
    if (firstUrl == secondUrl) return true
    val firstPath = fileUrlPath(firstUrl) ?: return false
    val secondPath = fileUrlPath(secondUrl) ?: return false
    if (!pathExistsForIdentity(firstPath) || !pathExistsForIdentity(secondPath)) return false
    return try {
      pathsReferToSameFile(firstPath, secondPath)
    } catch (exception: Exception) {
      throw identityConflict(exception)
    }
  }

  private fun fileUrlPath(url: String): Path? {
    if (!url.startsWith("file://")) return null
    return try {
      Path.of(VfsUtilCore.urlToPath(url))
    } catch (exception: Exception) {
      throw identityConflict(exception)
    }
  }

  private fun pathExistsForIdentity(path: Path): Boolean = try {
    Files.readAttributes(path, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
    true
  } catch (_: NoSuchFileException) {
    false
  } catch (_: NotDirectoryException) {
    false
  } catch (exception: Exception) {
    throw identityConflict(exception)
  }

  private fun identityConflict(cause: Throwable) = ProjectModelApplyException(
    ProjectModelErrorCode.OWNERSHIP_CONFLICT,
    "Unable to verify a project model path filesystem identity.",
    cause,
  )

  private fun invalidState(message: String, cause: Throwable? = null) =
    ProjectModelApplyException(ProjectModelErrorCode.INVALID_OWNERSHIP_STATE, message, cause)

  private fun ownershipConflict(message: String) =
    ProjectModelApplyException(ProjectModelErrorCode.OWNERSHIP_CONFLICT, message)

  private fun ensureMutationAllowed() {
    if (isProjectDisposed()) {
      throw ProjectModelApplyException(
        ProjectModelErrorCode.PROJECT_DISPOSED,
        "Project was disposed before the ReqWS project model update committed.",
      )
    }
    if (!isTrusted()) {
      throw ProjectModelApplyException(
        ProjectModelErrorCode.UNTRUSTED_PROJECT,
        "ReqWS project model updates are disabled for untrusted projects.",
      )
    }
  }

  private fun persistProjectState(message: String) {
    try {
      persistPendingState()
    } catch (exception: ProjectModelApplyException) {
      throw exception
    } catch (exception: Exception) {
      throw ProjectModelApplyException(
        ProjectModelErrorCode.OWNERSHIP_CONFLICT,
        message,
        exception,
      )
    }
  }

  private fun requireExpectedModel(
    workspaceModel: WorkspaceModel,
    target: TargetRoot,
    ownershipProofUrls: Collection<VirtualFileUrl>,
    expectedExcludedUrls: List<String>,
    changedMessage: String,
  ) {
    val snapshot = workspaceModel.currentSnapshot
    val module = snapshot.resolve(ModuleId(target.moduleName))
      ?: throw ownershipConflict("The target module disappeared while the ReqWS transition was saved.")
    val contentRoot = module.contentRoots.singleOrNull { it.url.url == target.url.url }
      ?: throw ownershipConflict(
        "The target workspace Content Root changed while the ReqWS transition was saved.",
      )
    ensureNoNestedContentRootConflict(snapshot, target, ownershipProofUrls)
    if (contentRoot.excludedUrls.map { entity -> entity.url.url } != expectedExcludedUrls) {
      throw ownershipConflict(changedMessage)
    }
  }

  private data class TargetRoot(
    val moduleName: String,
    val url: VirtualFileUrl,
  )

  private data class ValidatedOwnership(
    val stateVersion: Int,
    val targetModuleName: String,
    val managedExcludes: Map<String, String>,
    val pendingAdds: Map<String, String>,
    val pendingRemovals: Map<String, String>,
  )

  private data class TransactionOutcome(
    val ownership: Map<String, String>,
    val preparedOwnership: Map<String, String>,
    val preparedPendingAdds: Map<String, String>,
    val preparedPendingRemovals: Map<String, String>,
    val added: Set<String>,
    val removed: Set<String>,
    val kept: Set<String>,
    val borrowed: Set<String>,
    val stale: Set<String>,
  )
}
