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
    canonicalizeVcsMappings(vcsManager.getDirectoryMappings().toList())

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
    val canonicalMappings = canonicalizeVcsMappings(mappings)
    configurationMonitor.runPluginWrite(canonicalMappings) {
      vcsManager.setDirectoryMappings(canonicalMappings)
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
    var current = platform.getVersionedDirectoryMappings().platformCanonicalized()
    var planningOwnership = currentOwnership
    var mappingsCommitted = false
    var ownershipCommitted = false
    var converged: MappingCommit? = null
    var attempts = 0
    while (attempts < MAX_COMMIT_ATTEMPTS && converged == null) {
      attempts += 1
      val plannedFrom = current.platformCanonicalized()
      // A published external snapshot can lag the live list because platform writers mutate first
      // and publish a payload-less event later. Merge only histories that are provably compatible;
      // an ambiguous pending-only deletion or same-directory replacement must never be guessed.
      val planningMappings = mergePendingExternalWithLive(plannedFrom)
      if (planningMappings == null) {
        // The payload-less event for a deletion or same-directory replacement may not have been
        // published yet. Neither list can safely win because the platform would silently discard
        // the other one.
        current = platform.awaitQuiescentDirectoryMappings().platformCanonicalized()
        continue
      }
      val effectivePlanningOwnership = linkedMapOf<String, VcsMappingOwnership>().apply {
        planningOwnership.forEach { ownership ->
          // A payload-less external event cannot prove that a structurally equal mapping is still
          // the object ReqWS created. Conservatively revoke deletion authority before replanning.
          val safeOwnership = if (
            plannedFrom.pendingExternal != null &&
            ownership.kind == VcsMappingOwnershipKind.CREATED
          ) {
            ownership.copy(kind = VcsMappingOwnershipKind.BORROWED)
          } else {
            ownership
          }
          put(safeOwnership.relativeDirectory, safeOwnership)
        }
      }.values.toList()
      val proposedPlan = plan(snapshot, planningMappings, effectivePlanningOwnership, desired)
      val expectedMappings = mergedMappings(proposedPlan, planningMappings)
      val requiresMappingWrite = proposedPlan.mappingsChanged ||
        !sameMappings(expectedMappings, plannedFrom.mappings)
      val requiresOwnershipDemotion = effectivePlanningOwnership != planningOwnership
      val requiresOwnershipTransition = requiresMappingWrite || requiresOwnershipDemotion
      val ownershipCommits = prepareOwnershipCommits(
        snapshot = snapshot,
        plan = proposedPlan,
        currentMappings = planningMappings,
        actualMappings = plannedFrom.mappings,
        expectedMappings = expectedMappings,
        currentOwnership = effectivePlanningOwnership,
        recorder = ownershipRecorder,
        transitionRequired = requiresOwnershipTransition,
      )

      // First serialize only the equality/gate check. The verified atomic file write must remain
      // outside EDT, and a second equality check follows it before any VCS mapping mutation.
      val preflight = checkCurrentInWriteContext(
        expected = plannedFrom,
        stage = if (requiresOwnershipTransition) {
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

      if (requiresOwnershipTransition) {
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
      }

      if (requiresMappingWrite) {
        var writeResult: VersionedVcsMappings? = null
        platform.runInDirectoryMappingsWriteContext {
          val latest = platform.getVersionedDirectoryMappings().platformCanonicalized()
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

      val quiescent = platform.awaitQuiescentDirectoryMappings().platformCanonicalized()
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
        val acknowledged = platform.getVersionedDirectoryMappings().platformCanonicalized()
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
      val verified = platform.awaitQuiescentDirectoryMappings().platformCanonicalized()
      if (
        !verified.quiescent ||
        verified.pendingExternal != null ||
        !sameMappings(expectedMappings, verified.mappings)
      ) {
        // Final state can mint or retain CREATED authority. If a reverse-order writer is observed,
        // durably revoke every stable deletion claim before replanning, even when this attempt had
        // no mapping transition. Preserve pending tombstones so a crash cannot resurrect authority.
        val recoveryState = ownershipCommits.transitionState.copy(
          stableMappings = ownershipCommits.transitionState.stableMappings.map { ownership ->
            if (ownership.kind == VcsMappingOwnershipKind.CREATED) {
              ownership.copy(kind = VcsMappingOwnershipKind.BORROWED)
            } else {
              ownership
            }
          },
        )
        val recoveryCommit = prepareOwnershipCommit(
          state = recoveryState,
          recorder = ownershipRecorder,
          mappingsCommitted = mappingsCommitted,
          ownershipCommitted = ownershipCommitted,
        )
        persistOwnership(
          recoveryCommit,
          mappingsCommitted = mappingsCommitted,
          ownershipCommitted = ownershipCommitted,
        )
        ownershipCommitted = true
        planningOwnership = recoveryState.stableMappings
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
    val additionRelativePaths = plan.additions.mapTo(linkedSetOf()) { it.relativeDirectory }
    currentOwnership.forEach { ownership ->
      if (ownership.kind != VcsMappingOwnershipKind.CREATED) return@forEach
      if (plan.nextOwnership.none { next ->
          next.kind == VcsMappingOwnershipKind.CREATED &&
            next.relativeDirectory == ownership.relativeDirectory
        }
      ) {
        return@forEach
      }
      val owned = VcsPathIdentity.resolveOwned(
        snapshot.canonicalProjectRoot,
        ownership.relativeDirectory,
      ) ?: return@forEach
      val actualProofCount = actualMappings.count { mapping ->
        mapping.isExactUncustomizedGit(owned.directory)
      }
      val expectedProofCount = expectedMappings.count { mapping ->
        mapping.isExactUncustomizedGit(owned.directory)
      }
      if (actualProofCount == 0 && expectedProofCount == 1) {
        // Any actual-absent -> expected-present mutation is a real platform addition. Revoke
        // stable deletion authority before the whole-list set, even when the planner delta was
        // derived from a merged live view instead of a direct missing-root addition.
        additionRelativePaths.add(ownership.relativeDirectory)
      }
    }
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
  ): List<VcsDirectoryMapping> = canonicalizeVcsMappings(
    current.filterIndexed { index, _ -> index !in plan.removalIndices } +
      plan.additions.map { addition ->
        VcsDirectoryMapping(addition.directory, GIT_VCS_NAME)
      },
  )

  /**
   * A pending external snapshot is the newest published event, not necessarily the newest live
   * list. Preserve complete live-only additions. A pending-only entry could be either a mapping a
   * prior ReqWS write overwrote or an unpublished user deletion; the public API cannot distinguish
   * those histories. Pending-only and conflicting same-directory objects therefore wait or fail
   * closed rather than guessing a winner.
   */
  private fun mergePendingExternalWithLive(
    snapshot: VersionedVcsMappings,
  ): List<VcsDirectoryMapping>? {
    val external = snapshot.pendingExternal ?: return canonicalizeVcsMappings(snapshot.mappings)
    val liveByDirectory = snapshot.mappings.associateBy(VcsDirectoryMapping::getDirectory)
    val mergedByDirectory = linkedMapOf<String, VcsDirectoryMapping>()
    external.mappings.forEach { retained ->
      val live = liveByDirectory[retained.directory] ?: return null
      if (retained != live) return null
      mergedByDirectory[retained.directory] = retained
    }
    snapshot.mappings.forEach { live ->
      if (live.directory !in mergedByDirectory) mergedByDirectory[live.directory] = live
    }
    return canonicalizeVcsMappings(mergedByDirectory.values.toList())
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
  ): Boolean = canonicalizeVcsMappings(first) == canonicalizeVcsMappings(second)

  private fun sameSnapshot(
    first: VersionedVcsMappings,
    second: VersionedVcsMappings,
  ): Boolean {
    val canonicalFirst = first.platformCanonicalized()
    val canonicalSecond = second.platformCanonicalized()
    return canonicalFirst.revision == canonicalSecond.revision &&
      canonicalFirst.mappings == canonicalSecond.mappings &&
      canonicalFirst.pendingExternal == canonicalSecond.pendingExternal
  }

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
