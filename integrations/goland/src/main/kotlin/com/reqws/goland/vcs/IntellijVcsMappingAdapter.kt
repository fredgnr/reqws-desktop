package com.reqws.goland.vcs

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.vcs.ProjectLevelVcsManager
import com.intellij.openapi.vcs.VcsDirectoryMapping
import com.reqws.goland.manifest.ManifestSnapshot
import git4idea.repo.GitRepositoryManager
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.util.UUID

internal interface VcsMappingPlatform {
  fun isGitAvailable(): Boolean

  fun getDirectoryMappings(): List<VcsDirectoryMapping>

  fun getVersionedDirectoryMappings(): VersionedVcsMappings =
    VersionedVcsMappings(revision = 0, mappings = getDirectoryMappings())

  fun awaitQuiescentDirectoryMappings(): VersionedVcsMappings =
    getVersionedDirectoryMappings()

  fun acknowledgeExternalMappings(revision: Long) = Unit

  fun setDirectoryMappings(mappings: List<VcsDirectoryMapping>)

  fun runInDirectoryMappingsWriteContext(action: () -> Unit)

  fun refreshGitRepositories()
}

internal class IntellijVcsMappingPlatform(private val project: Project) : VcsMappingPlatform {
  private val vcsManager: ProjectLevelVcsManager
    get() = ProjectLevelVcsManager.getInstance(project)

  override fun isGitAvailable(): Boolean = vcsManager.findVcsByName(GIT_VCS_NAME) != null

  override fun getDirectoryMappings(): List<VcsDirectoryMapping> =
    vcsManager.getDirectoryMappings().toList()

  private val configurationMonitor: ReqwsVcsConfigurationMonitor
    get() = project.service()

  override fun getVersionedDirectoryMappings(): VersionedVcsMappings =
    configurationMonitor.snapshot()

  override fun awaitQuiescentDirectoryMappings(): VersionedVcsMappings =
    configurationMonitor.awaitQuiescentSnapshot()

  override fun acknowledgeExternalMappings(revision: Long) {
    configurationMonitor.acknowledgeExternalSnapshot(revision)
  }

  override fun setDirectoryMappings(mappings: List<VcsDirectoryMapping>) {
    // This public API performs the platform's mapped-root update; updateActiveVcss is deprecated.
    configurationMonitor.runPluginWrite(mappings) {
      vcsManager.setDirectoryMappings(mappings)
    }
  }

  override fun runInDirectoryMappingsWriteContext(action: () -> Unit) {
    val application = ApplicationManager.getApplication()
    if (application.isDispatchThread) {
      action()
    } else {
      // The platform VCS Settings configurable applies its final mapping read/write on EDT.
      // Use the same stable, public 261 execution context so a user Apply cannot commit between
      // ReqWS's final equality check and the whole-list setDirectoryMappings call.
      var failure: Throwable? = null
      application.invokeAndWait {
        try {
          action()
        } catch (error: Throwable) {
          failure = error
        }
      }
      failure?.let { throw it }
    }
  }

  override fun refreshGitRepositories() {
    GitRepositoryManager.getInstance(project).updateAllRepositories()
  }
}

internal class IntellijVcsMappingAdapter(
  private val platform: VcsMappingPlatform,
  private val planner: VcsMappingPlanner = VcsMappingPlanner(),
  private val isProjectDisposed: () -> Boolean = { false },
  private val isProjectTrusted: () -> Boolean = { true },
  private val operationTokenFactory: () -> String = {
    UUID.randomUUID().toString().replace("-", "")
  },
) {
  fun apply(
    snapshot: ManifestSnapshot,
    currentOwnership: List<VcsMappingOwnership>,
    ownershipRecorder: VcsMappingOwnershipRecorder,
  ): VcsMappingApplyResult {
    if (!platform.isGitAvailable()) {
      throw VcsMappingApplyException(
        code = VcsMappingApplyErrorCode.GIT_PLUGIN_UNAVAILABLE,
        stage = VcsMappingApplyStage.AVAILABILITY,
        mappingsCommitted = false,
        ownershipCommitted = false,
      )
    }

    val desired = desiredRoots(snapshot)
    var current = platform.getVersionedDirectoryMappings()
    var planningOwnership = currentOwnership
    var mappingsCommitted = false
    var ownershipCommitted = false
    var converged: MappingCommit? = null
    var attempts = 0
    while (attempts < MAX_COMMIT_ATTEMPTS && converged == null) {
      attempts += 1
      val plannedFrom = current
      // An external writer can publish A+U after ReqWS's final read and then be overwritten by
      // ReqWS's whole-list A+R set. Plan from the retained external A+U baseline, while all commit
      // checks below still compare against the actual live mapping snapshot.
      val planningMappings = plannedFrom.pendingExternal?.mappings ?: plannedFrom.mappings
      val proposedPlan = plan(snapshot, planningMappings, planningOwnership, desired)
      val expectedMappings = mergedMappings(proposedPlan, planningMappings)
      val requiresMappingWrite = proposedPlan.mappingsChanged ||
        !sameMappings(expectedMappings, plannedFrom.mappings)
      val ownershipCommits = prepareOwnershipCommits(
        snapshot = snapshot,
        plan = proposedPlan,
        currentMappings = planningMappings,
        actualMappings = plannedFrom.mappings,
        expectedMappings = expectedMappings,
        currentOwnership = planningOwnership,
        recorder = ownershipRecorder,
        transitionRequired = requiresMappingWrite,
      )

      // First serialize only the equality/gate check. The verified atomic file write must remain
      // outside EDT, and a second equality check follows it before any VCS mapping mutation.
      val preflight = checkCurrentInWriteContext(
        expected = plannedFrom,
        stage = if (requiresMappingWrite) {
          VcsMappingApplyStage.OWNERSHIP
        } else {
          VcsMappingApplyStage.MAPPINGS
        },
        mappingsCommitted = mappingsCommitted,
        ownershipCommitted = ownershipCommitted,
      )
      if (preflight != null) {
        current = preflight
        continue
      }

      if (requiresMappingWrite) {
        ensureMutationAllowed(
          stage = VcsMappingApplyStage.OWNERSHIP,
          mappingsCommitted = mappingsCommitted,
          ownershipCommitted = ownershipCommitted,
        )
        persistOwnership(
          ownershipCommits.transition,
          mappingsCommitted = mappingsCommitted,
          ownershipCommitted = ownershipCommitted,
        )
        ownershipCommitted = true
        // Once the journal is durable, its pending phases are tombstones. If a writer invalidates
        // this plan, retries must not reconstruct deletion authority from the old stable input.
        planningOwnership = ownershipCommits.transitionState.stableMappings

        var writeResult: VersionedVcsMappings? = null
        platform.runInDirectoryMappingsWriteContext {
          val latest = platform.getVersionedDirectoryMappings()
          if (!sameSnapshot(plannedFrom, latest)) {
            writeResult = latest
            return@runInDirectoryMappingsWriteContext
          }
          ensureMutationAllowed(
            stage = VcsMappingApplyStage.MAPPINGS,
            mappingsCommitted = mappingsCommitted,
            ownershipCommitted = ownershipCommitted,
          )
          try {
            platform.setDirectoryMappings(expectedMappings)
            mappingsCommitted = true
          } catch (exception: Exception) {
            throw VcsMappingApplyException(
              code = VcsMappingApplyErrorCode.VCS_MAPPING_APPLY_FAILED,
              stage = VcsMappingApplyStage.MAPPINGS,
              mappingsCommitted = mappingsCommitted,
              ownershipCommitted = ownershipCommitted,
              cause = exception,
            )
          }
        }
        if (writeResult != null) {
          current = requireNotNull(writeResult)
          continue
        }
      }

      val quiescent = platform.awaitQuiescentDirectoryMappings()
      if (
        !quiescent.quiescent ||
        !sameMappings(expectedMappings, quiescent.mappings) ||
        quiescent.pendingExternal?.revision != plannedFrom.pendingExternal?.revision
      ) {
        current = quiescent
        continue
      }
      val finalCheck = checkCurrentInWriteContext(
        expected = quiescent,
        stage = VcsMappingApplyStage.OWNERSHIP,
        mappingsCommitted = mappingsCommitted,
        ownershipCommitted = ownershipCommitted,
      )
      if (finalCheck != null || !sameMappings(expectedMappings, quiescent.mappings)) {
        current = finalCheck ?: quiescent
        continue
      }

      val plannedExternal = plannedFrom.pendingExternal
      if (plannedExternal != null) {
        platform.acknowledgeExternalMappings(plannedExternal.revision)
        val acknowledged = platform.getVersionedDirectoryMappings()
        if (
          acknowledged.pendingExternal != null ||
          !sameMappings(expectedMappings, acknowledged.mappings)
        ) {
          current = acknowledged
          continue
        }
      }

      ensureMutationAllowed(
        stage = VcsMappingApplyStage.OWNERSHIP,
        mappingsCommitted = mappingsCommitted,
        ownershipCommitted = ownershipCommitted,
      )
      val finalOwnershipCommit = prepareOwnershipCommit(
        state = ownershipCommits.finalState,
        recorder = ownershipRecorder,
        mappingsCommitted = mappingsCommitted,
        ownershipCommitted = ownershipCommitted,
      )
      persistOwnership(
        finalOwnershipCommit,
        mappingsCommitted = mappingsCommitted,
        ownershipCommitted = ownershipCommitted,
      )
      ownershipCommitted = true
      planningOwnership = proposedPlan.nextOwnership

      // Detect a reverse-order writer that completes while the final ownership file is flushed.
      // A still-later event is handled by the production monitor's external-change listener.
      val verified = platform.awaitQuiescentDirectoryMappings()
      if (
        !verified.quiescent ||
        verified.pendingExternal != null ||
        !sameMappings(expectedMappings, verified.mappings)
      ) {
        // Final state can mint CREATED authority for additions. If a reverse-order writer is
        // observed, durably demote it back to the non-authorizing transition before replanning.
        val recoveryCommit = if (ownershipCommits.transition != null) {
          prepareOwnershipCommit(
            state = ownershipCommits.transitionState,
            recorder = ownershipRecorder,
            mappingsCommitted = mappingsCommitted,
            ownershipCommitted = ownershipCommitted,
          )
        } else {
          null
        }
        persistOwnership(
          recoveryCommit,
          mappingsCommitted = mappingsCommitted,
          ownershipCommitted = ownershipCommitted,
        )
        if (ownershipCommits.transition != null) ownershipCommitted = true
        planningOwnership = ownershipCommits.transitionState.stableMappings
        current = verified
        continue
      }
      converged = MappingCommit(
        plan = proposedPlan,
        mappingsCommitted = mappingsCommitted,
        ownershipCommitted = ownershipCommitted,
      )
    }
    val commit = converged ?: throw VcsMappingApplyException(
      code = VcsMappingApplyErrorCode.VCS_MAPPING_APPLY_FAILED,
      stage = VcsMappingApplyStage.MAPPINGS,
      mappingsCommitted = mappingsCommitted,
      ownershipCommitted = ownershipCommitted,
    )

    ensureMutationAllowed(
      stage = VcsMappingApplyStage.REFRESH,
      mappingsCommitted = commit.mappingsCommitted,
      ownershipCommitted = commit.ownershipCommitted,
    )
    try {
      // Always refresh on an actual apply attempt. If a previous refresh failed after mappings
      // committed, the retry plan is unchanged but still has to finish this stage.
      platform.refreshGitRepositories()
    } catch (exception: Exception) {
      throw VcsMappingApplyException(
        code = VcsMappingApplyErrorCode.VCS_MAPPING_APPLY_FAILED,
        stage = VcsMappingApplyStage.REFRESH,
        mappingsCommitted = commit.mappingsCommitted,
        ownershipCommitted = commit.ownershipCommitted,
        cause = exception,
      )
    }
    ensureMutationAllowed(
      stage = VcsMappingApplyStage.REFRESH,
      mappingsCommitted = commit.mappingsCommitted,
      ownershipCommitted = commit.ownershipCommitted,
    )

    return VcsMappingApplyResult(
      plan = commit.plan,
      mappingsCommitted = commit.mappingsCommitted,
      ownershipCommitted = commit.ownershipCommitted,
      refreshed = true,
    )
  }

  private fun prepareOwnershipCommits(
    snapshot: ManifestSnapshot,
    plan: VcsMappingPlan,
    currentMappings: List<VcsDirectoryMapping>,
    actualMappings: List<VcsDirectoryMapping>,
    expectedMappings: List<VcsDirectoryMapping>,
    currentOwnership: List<VcsMappingOwnership>,
    recorder: VcsMappingOwnershipRecorder,
    transitionRequired: Boolean,
  ): PreparedOwnershipCommits {
    val additionRelativePaths = plan.additions.mapTo(hashSetOf()) { it.relativeDirectory }
    val transitionOwnership = plan.nextOwnership.filterNot { ownership ->
      ownership.relativeDirectory in additionRelativePaths
    }
    val removedDirectories = plan.removalIndices.mapNotNull { index ->
      currentMappings.getOrNull(index)?.directory
    }
    val removalRelativePaths = currentOwnership.filter { ownership ->
      if (ownership.kind != VcsMappingOwnershipKind.CREATED) return@filter false
      val owned = VcsPathIdentity.resolveOwned(
        snapshot.canonicalProjectRoot,
        ownership.relativeDirectory,
      ) ?: return@filter false
      val explicitlyRemovedByPlan = removedDirectories.any { directory ->
        VcsPathIdentity.sameStoredDirectory(directory, owned.directory)
      }
      val removedWhileRestoringExternalBaseline =
        actualMappings.any { mapping -> mapping.isExactUncustomizedGit(owned.directory) } &&
          expectedMappings.none { mapping -> mapping.isExactUncustomizedGit(owned.directory) }
      explicitlyRemovedByPlan || removedWhileRestoringExternalBaseline
    }.map { it.relativeDirectory }
    val transitionState = VcsMappingOwnershipState(
      stableMappings = transitionOwnership,
      pendingAdds = additionRelativePaths.sorted().map { relative ->
        VcsMappingPendingOwnership(relative, operationTokenFactory())
      },
      pendingRemovals = removalRelativePaths.sorted().map { relative ->
        VcsMappingPendingOwnership(relative, operationTokenFactory())
      },
    )
    val finalState = VcsMappingOwnershipState(stableMappings = plan.nextOwnership)
    return try {
      val transition = if (transitionRequired) {
        recorder.prepare(transitionState)
      } else {
        null
      }
      PreparedOwnershipCommits(
        transitionState = transitionState,
        transition = transition,
        finalState = finalState,
      )
    } catch (exception: Exception) {
      throw VcsMappingApplyException(
        code = VcsMappingApplyErrorCode.VCS_MAPPING_APPLY_FAILED,
        stage = VcsMappingApplyStage.OWNERSHIP,
        mappingsCommitted = false,
        ownershipCommitted = false,
        cause = exception,
      )
    }
  }

  private fun VcsDirectoryMapping.isExactUncustomizedGit(ownedDirectory: String): Boolean =
    vcs == GIT_VCS_NAME &&
      rootSettings == null &&
      VcsPathIdentity.sameStoredDirectory(directory, ownedDirectory)

  private fun prepareOwnershipCommit(
    state: VcsMappingOwnershipState,
    recorder: VcsMappingOwnershipRecorder,
    mappingsCommitted: Boolean,
    ownershipCommitted: Boolean,
  ): VcsMappingOwnershipCommit = try {
    recorder.prepare(state)
  } catch (exception: Exception) {
    throw VcsMappingApplyException(
      code = VcsMappingApplyErrorCode.VCS_MAPPING_APPLY_FAILED,
      stage = VcsMappingApplyStage.OWNERSHIP,
      mappingsCommitted = mappingsCommitted,
      ownershipCommitted = ownershipCommitted,
      cause = exception,
    )
  }

  private fun persistOwnership(
    commit: VcsMappingOwnershipCommit?,
    mappingsCommitted: Boolean,
    ownershipCommitted: Boolean,
  ) {
    if (commit == null) return
    try {
      commit.persistAndVerify()
    } catch (exception: Exception) {
      throw VcsMappingApplyException(
        code = VcsMappingApplyErrorCode.VCS_MAPPING_APPLY_FAILED,
        stage = VcsMappingApplyStage.OWNERSHIP,
        mappingsCommitted = mappingsCommitted,
        ownershipCommitted = ownershipCommitted,
        cause = exception,
      )
    }
  }

  private fun checkCurrentInWriteContext(
    expected: VersionedVcsMappings,
    stage: VcsMappingApplyStage,
    mappingsCommitted: Boolean,
    ownershipCommitted: Boolean,
  ): VersionedVcsMappings? {
    var stale: VersionedVcsMappings? = null
    platform.runInDirectoryMappingsWriteContext {
      val latest = platform.getVersionedDirectoryMappings()
      if (!sameSnapshot(expected, latest)) {
        stale = latest
      } else {
        ensureMutationAllowed(
          stage = stage,
          mappingsCommitted = mappingsCommitted,
          ownershipCommitted = ownershipCommitted,
        )
      }
    }
    return stale
  }

  private fun mergedMappings(
    plan: VcsMappingPlan,
    current: List<VcsDirectoryMapping>,
  ): List<VcsDirectoryMapping> =
    current.filterIndexed { index, _ -> index !in plan.removalIndices } +
      plan.additions.map { addition ->
        VcsDirectoryMapping(addition.directory, GIT_VCS_NAME)
      }

  private fun plan(
    snapshot: ManifestSnapshot,
    current: List<VcsDirectoryMapping>,
    currentOwnership: List<VcsMappingOwnership>,
    desired: List<DesiredVcsRoot>,
  ): VcsMappingPlan = planner.plan(
    projectRoot = snapshot.canonicalProjectRoot,
    currentMappings = current.mapIndexed { index, mapping ->
      CurrentVcsMapping(
        index = index,
        directory = mapping.directory,
        vcs = mapping.vcs,
        hasRootSettings = mapping.rootSettings != null,
        lexicalIdentity = VcsPathIdentity.mappingLexical(
          snapshot.canonicalProjectRoot,
          mapping.directory,
        ),
        canonicalIdentity = VcsPathIdentity.mappingCanonical(
          snapshot.canonicalProjectRoot,
          mapping.directory,
        ),
      )
    },
    currentOwnership = currentOwnership,
    desiredRoots = desired,
  )

  private fun sameMappings(
    first: List<VcsDirectoryMapping>,
    second: List<VcsDirectoryMapping>,
  ): Boolean = first == second

  private fun sameSnapshot(
    first: VersionedVcsMappings,
    second: VersionedVcsMappings,
  ): Boolean = first.revision == second.revision &&
    sameMappings(first.mappings, second.mappings) &&
    first.pendingExternal == second.pendingExternal

  private fun ensureMutationAllowed(
    stage: VcsMappingApplyStage,
    mappingsCommitted: Boolean,
    ownershipCommitted: Boolean,
  ) {
    val code = when {
      isProjectDisposed() -> VcsMappingApplyErrorCode.PROJECT_DISPOSED
      !isProjectTrusted() -> VcsMappingApplyErrorCode.SAFE_MODE_BLOCKED
      else -> return
    }
    throw VcsMappingApplyException(
      code = code,
      stage = stage,
      mappingsCommitted = mappingsCommitted,
      ownershipCommitted = ownershipCommitted,
    )
  }

  private fun desiredRoots(snapshot: ManifestSnapshot): List<DesiredVcsRoot> =
    snapshot.repositories.mapIndexed { index, resolved ->
      val candidate = snapshot.canonicalProjectRoot
        .resolve(resolved.repository.relativePath)
        .normalize()
      if (!Files.exists(candidate, LinkOption.NOFOLLOW_LINKS)) {
        DesiredVcsRoot(
          repositoryIndex = index,
          relativeDirectory = resolved.repository.relativePath,
          directory = null,
          availability = DesiredVcsRootAvailability.MISSING,
        )
      } else {
        val canonicalPath = try {
          candidate.toRealPath()
        } catch (_: Exception) {
          null
        }
        val stableRepository = canonicalPath != null &&
          canonicalPath != snapshot.canonicalProjectRoot &&
          canonicalPath.startsWith(snapshot.canonicalProjectRoot) &&
          Files.isDirectory(canonicalPath, LinkOption.NOFOLLOW_LINKS)
        val relativeDirectory = if (stableRepository) {
          snapshot.canonicalProjectRoot.relativize(requireNotNull(canonicalPath)).toString()
        } else {
          resolved.repository.relativePath
        }
        DesiredVcsRoot(
          repositoryIndex = index,
          relativeDirectory = relativeDirectory,
          directory = canonicalPath?.toString(),
          availability = if (
            stableRepository &&
            Files.isDirectory(requireNotNull(canonicalPath).resolve(".git"), LinkOption.NOFOLLOW_LINKS)
          ) {
            DesiredVcsRootAvailability.PRESENT_GIT
          } else {
            DesiredVcsRootAvailability.NOT_GIT_REPOSITORY
          },
        )
      }
    }

  companion object {
    private const val MAX_COMMIT_ATTEMPTS = 5
  }
}

private data class MappingCommit(
  val plan: VcsMappingPlan,
  val mappingsCommitted: Boolean,
  val ownershipCommitted: Boolean,
)

private data class PreparedOwnershipCommits(
  val transitionState: VcsMappingOwnershipState,
  val transition: VcsMappingOwnershipCommit?,
  val finalState: VcsMappingOwnershipState,
)
