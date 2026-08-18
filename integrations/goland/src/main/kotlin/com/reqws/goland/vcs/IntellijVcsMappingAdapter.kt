package com.reqws.goland.vcs

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vcs.ProjectLevelVcsManager
import com.intellij.openapi.vcs.VcsDirectoryMapping
import com.reqws.goland.manifest.ManifestSnapshot
import git4idea.repo.GitRepositoryManager
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path

internal interface VcsMappingPlatform {
  fun isGitAvailable(): Boolean

  fun getDirectoryMappings(): List<VcsDirectoryMapping>

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

  override fun setDirectoryMappings(mappings: List<VcsDirectoryMapping>) {
    // This public API performs the platform's mapped-root update; updateActiveVcss is deprecated.
    vcsManager.setDirectoryMappings(mappings)
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
    var current = platform.getDirectoryMappings()
    var attempts = 0
    var committed: MappingCommit? = null
    while (attempts < MAX_COMMIT_ATTEMPTS && committed == null) {
      // Filesystem identity work remains on the caller's background context. The resulting plan
      // is valid only for this exact, full mapping list (VcsDirectoryMapping equality includes
      // rootSettings); the serialized final check below rejects it if the list has changed.
      val plannedFrom = current
      val proposedPlan = plan(snapshot, plannedFrom, currentOwnership, desired)
      // Ownership path validation can call Files.exists/toRealPath. Prepare immutable commits
      // before entering the EDT write context; stale plans simply discard these prepared values.
      val ownershipCommits = prepareOwnershipCommits(proposedPlan, ownershipRecorder)
      var attempt: MappingCommitAttempt? = null
      platform.runInDirectoryMappingsWriteContext {
        val latest = platform.getDirectoryMappings()
        attempt = if (!sameMappings(plannedFrom, latest)) {
          MappingCommitAttempt.Stale(latest)
        } else {
          MappingCommitAttempt.Committed(
            commitPlan(
              plan = proposedPlan,
              current = latest,
              ownershipCommits = ownershipCommits,
            ),
          )
        }
      }
      when (val completed = checkNotNull(attempt)) {
        is MappingCommitAttempt.Stale -> current = completed.current
        is MappingCommitAttempt.Committed -> committed = completed.commit
      }
      attempts += 1
    }
    val commit = committed ?: run {
      throw VcsMappingApplyException(
        code = VcsMappingApplyErrorCode.VCS_MAPPING_APPLY_FAILED,
        stage = VcsMappingApplyStage.MAPPINGS,
        mappingsCommitted = false,
        ownershipCommitted = false,
      )
    }

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

  private fun commitPlan(
    plan: VcsMappingPlan,
    current: List<VcsDirectoryMapping>,
    ownershipCommits: PreparedOwnershipCommits,
  ): MappingCommit {
    var mappingsCommitted = false
    var ownershipCommitted = false
    ownershipCommits.preRemoval?.let { preRemoval ->
      // Revoke deletion authority before committing a destructive mapping removal. If the
      // process stops after this point, the old mapping may remain, but a future same-path user
      // mapping can never inherit a stale CREATED claim.
      ensureMutationAllowed(
        stage = VcsMappingApplyStage.OWNERSHIP,
        mappingsCommitted = false,
        ownershipCommitted = false,
      )
      try {
        preRemoval.commit()
        ownershipCommitted = true
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
    if (plan.mappingsChanged) {
      ensureMutationAllowed(
        stage = VcsMappingApplyStage.MAPPINGS,
        mappingsCommitted = false,
        ownershipCommitted = ownershipCommitted,
      )
      val merged = current.filterIndexed { index, _ -> index !in plan.removalIndices } +
        plan.additions.map { addition ->
          VcsDirectoryMapping(addition.directory, GIT_VCS_NAME)
        }
      try {
        platform.setDirectoryMappings(merged)
        mappingsCommitted = true
      } catch (exception: Exception) {
        throw VcsMappingApplyException(
          code = VcsMappingApplyErrorCode.VCS_MAPPING_APPLY_FAILED,
          stage = VcsMappingApplyStage.MAPPINGS,
          mappingsCommitted = false,
          ownershipCommitted = ownershipCommitted,
          cause = exception,
        )
      }
    }

    // Record newly-created mapping evidence only after setDirectoryMappings succeeds. A refresh
    // failure must not orphan that mapping, while a failed set must never create deletion rights.
    ownershipCommits.postMapping?.let { finalOwnership ->
      ensureMutationAllowed(
        stage = VcsMappingApplyStage.OWNERSHIP,
        mappingsCommitted = mappingsCommitted,
        ownershipCommitted = ownershipCommitted,
      )
      try {
        finalOwnership.commit()
        ownershipCommitted = true
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
    return MappingCommit(
      plan = plan,
      mappingsCommitted = mappingsCommitted,
      ownershipCommitted = ownershipCommitted,
    )
  }

  private fun prepareOwnershipCommits(
    plan: VcsMappingPlan,
    ownershipRecorder: VcsMappingOwnershipRecorder,
  ): PreparedOwnershipCommits {
    val additionRelativePaths = plan.additions.mapTo(hashSetOf()) { it.relativeDirectory }
    val preRemovalOwnership = plan.nextOwnership.filterNot { ownership ->
      ownership.relativeDirectory in additionRelativePaths
    }
    return try {
      val preRemoval = if (plan.removalIndices.isNotEmpty()) {
        ownershipRecorder.prepare(preRemovalOwnership)
      } else {
        null
      }
      val postMapping = if (preRemoval == null || preRemovalOwnership != plan.nextOwnership) {
        ownershipRecorder.prepare(plan.nextOwnership)
      } else {
        null
      }
      PreparedOwnershipCommits(preRemoval = preRemoval, postMapping = postMapping)
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
    private const val MAX_COMMIT_ATTEMPTS = 3
  }
}

private sealed interface MappingCommitAttempt {
  data class Stale(val current: List<VcsDirectoryMapping>) : MappingCommitAttempt

  data class Committed(val commit: MappingCommit) : MappingCommitAttempt
}

private data class MappingCommit(
  val plan: VcsMappingPlan,
  val mappingsCommitted: Boolean,
  val ownershipCommitted: Boolean,
)

private data class PreparedOwnershipCommits(
  val preRemoval: VcsMappingOwnershipCommit?,
  val postMapping: VcsMappingOwnershipCommit?,
)
