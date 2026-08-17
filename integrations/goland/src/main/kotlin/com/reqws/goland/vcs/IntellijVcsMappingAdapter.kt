package com.reqws.goland.vcs

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
    var plan = plan(snapshot, current, currentOwnership, desired)
    var stable = false
    var attempts = 0
    while (attempts < MAX_STABILITY_READS) {
      val latest = platform.getDirectoryMappings()
      if (sameMappings(current, latest)) {
        stable = true
        break
      }
      current = latest
      plan = plan(snapshot, current, currentOwnership, desired)
      attempts += 1
    }
    if (!stable) {
      throw VcsMappingApplyException(
        code = VcsMappingApplyErrorCode.VCS_MAPPING_APPLY_FAILED,
        stage = VcsMappingApplyStage.MAPPINGS,
        mappingsCommitted = false,
        ownershipCommitted = false,
      )
    }

    var mappingsCommitted = false
    var ownershipCommitted = false
    val additionRelativePaths = plan.additions.mapTo(hashSetOf()) { it.relativeDirectory }
    val preRemovalOwnership = plan.nextOwnership.filterNot { ownership ->
      ownership.relativeDirectory in additionRelativePaths
    }
    if (plan.removalIndices.isNotEmpty()) {
      // Revoke deletion authority before committing a destructive mapping removal. If the
      // process stops after this point, the old mapping may remain, but a future same-path user
      // mapping can never inherit a stale CREATED claim.
      ensureMutationAllowed(
        stage = VcsMappingApplyStage.OWNERSHIP,
        mappingsCommitted = false,
        ownershipCommitted = false,
      )
      try {
        ownershipRecorder.replace(preRemovalOwnership)
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
    if (!ownershipCommitted || preRemovalOwnership != plan.nextOwnership) {
      ensureMutationAllowed(
        stage = VcsMappingApplyStage.OWNERSHIP,
        mappingsCommitted = mappingsCommitted,
        ownershipCommitted = ownershipCommitted,
      )
      try {
        ownershipRecorder.replace(plan.nextOwnership)
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

    ensureMutationAllowed(
      stage = VcsMappingApplyStage.REFRESH,
      mappingsCommitted = mappingsCommitted,
      ownershipCommitted = ownershipCommitted,
    )
    try {
      // Always refresh on an actual apply attempt. If a previous refresh failed after mappings
      // committed, the retry plan is unchanged but still has to finish this stage.
      platform.refreshGitRepositories()
    } catch (exception: Exception) {
      throw VcsMappingApplyException(
        code = VcsMappingApplyErrorCode.VCS_MAPPING_APPLY_FAILED,
        stage = VcsMappingApplyStage.REFRESH,
        mappingsCommitted = mappingsCommitted,
        ownershipCommitted = ownershipCommitted,
        cause = exception,
      )
    }

    return VcsMappingApplyResult(
      plan = plan,
      mappingsCommitted = mappingsCommitted,
      ownershipCommitted = ownershipCommitted,
      refreshed = true,
    )
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
    private const val MAX_STABILITY_READS = 3
  }
}
