package com.reqws.goland.vcs

import com.reqws.goland.manifest.ManifestSnapshot
import com.reqws.goland.manifest.RepositoryAvailability
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path

/** Pure classification of the platform's current mappings; it never proposes or performs writes. */
internal class VcsRootInspector {
  fun inspect(
    snapshot: ManifestSnapshot,
    gitAvailable: Boolean,
    mappings: List<ObservedVcsMapping>,
  ): VcsRootInspection {
    val projectRoot = snapshot.canonicalProjectRoot
    val rootIdentity = VcsPathIdentity.lexical(projectRoot)
    val mappingObservations = mappings.map { mapping ->
      MappingObservation(
        mapping = mapping,
        identities = VcsPathIdentity.mappingIdentities(projectRoot, mapping.directory),
      )
    }
    val activeIdentities = linkedSetOf<String>()
    val repositoryStatuses = snapshot.repositories.mapIndexed { index, resolved ->
      val candidate = projectRoot.resolve(resolved.repository.relativePath).normalize()
      val identities = VcsPathIdentity.repositoryIdentities(candidate, resolved.canonicalPath)
      activeIdentities.addAll(identities)
      val status = when {
        resolved.availability == RepositoryAvailability.MISSING ->
          VcsRepositoryStatus.MISSING_DIRECTORY
        !Files.exists(candidate, LinkOption.NOFOLLOW_LINKS) ->
          VcsRepositoryStatus.MISSING_DIRECTORY
        !isStableGitRepository(projectRoot, candidate) -> VcsRepositoryStatus.NOT_GIT
        !gitAvailable -> VcsRepositoryStatus.NOT_CONFIGURED
        else -> classifyConfiguredRepository(identities, mappingObservations)
      }
      VcsRepositoryInspection(index, status)
    }

    val workspaceDiagnostics = linkedSetOf<VcsWorkspaceDiagnosticCode>()
    if (!gitAvailable) {
      workspaceDiagnostics.add(VcsWorkspaceDiagnosticCode.GIT_PLUGIN_UNAVAILABLE)
    }
    mappingObservations.forEach { observation ->
      if (observation.mapping.vcs != GIT_VCS_NAME) return@forEach
      val isWorkspaceRoot = observation.mapping.directory.isEmpty() ||
        rootIdentity in observation.identities
      if (isWorkspaceRoot) {
        workspaceDiagnostics.add(VcsWorkspaceDiagnosticCode.WORKSPACE_WIDE_GIT_ROOT)
      } else if (
        observation.identities.any { VcsPathIdentity.isWithin(projectRoot, it) } &&
        observation.identities.none { it in activeIdentities } &&
        isRetainedGitRepository(projectRoot, observation.mapping.directory)
      ) {
        workspaceDiagnostics.add(VcsWorkspaceDiagnosticCode.INACTIVE_GIT_ROOT)
      }
    }

    return VcsRootInspection(
      repositoryStatuses = repositoryStatuses,
      workspaceDiagnostics = workspaceDiagnostics.toList(),
    )
  }

  private fun isStableGitRepository(projectRoot: Path, candidate: Path): Boolean {
    val canonical = candidate.toRealPath()
    return canonical != projectRoot &&
      canonical.startsWith(projectRoot) &&
      Files.isDirectory(canonical, LinkOption.NOFOLLOW_LINKS) &&
      Files.isDirectory(canonical.resolve(".git"), LinkOption.NOFOLLOW_LINKS)
  }

  private fun isRetainedGitRepository(projectRoot: Path, mappingDirectory: String): Boolean {
    val mappedPath = try {
      Path.of(mappingDirectory).let { path ->
        if (path.isAbsolute) path else projectRoot.resolve(path)
      }
    } catch (_: Exception) {
      return false
    }
    val canonical = try {
      mappedPath.toRealPath()
    } catch (_: Exception) {
      return false
    }
    return canonical.parent == projectRoot &&
      Files.isDirectory(canonical, LinkOption.NOFOLLOW_LINKS) &&
      Files.isDirectory(canonical.resolve(".git"), LinkOption.NOFOLLOW_LINKS)
  }

  private fun classifyConfiguredRepository(
    repositoryIdentities: Set<String>,
    mappings: List<MappingObservation>,
  ): VcsRepositoryStatus {
    val matching = mappings.filter { observation ->
      observation.identities.any { it in repositoryIdentities }
    }
    return when {
      matching.isEmpty() -> VcsRepositoryStatus.NOT_CONFIGURED
      matching.size > 1 -> VcsRepositoryStatus.DUPLICATE
      matching.single().mapping.vcs != GIT_VCS_NAME -> VcsRepositoryStatus.WRONG_VCS
      else -> VcsRepositoryStatus.CONFIGURED
    }
  }
}

private data class MappingObservation(
  val mapping: ObservedVcsMapping,
  val identities: Set<String>,
)
