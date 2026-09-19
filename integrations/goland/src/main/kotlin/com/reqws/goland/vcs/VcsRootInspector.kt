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
    val ideRoot = snapshot.loading?.binding?.shell ?: projectRoot
    val rootIdentity = VcsPathIdentity.lexical(projectRoot)
    val mappingObservations = mappings.map { mapping ->
      MappingObservation(
        mapping = mapping,
        identities = VcsPathIdentity.mappingIdentities(ideRoot, mapping.directory),
      )
    }
    val memberIdentities = snapshot.repositories.mapTo(linkedSetOf()) { VcsPathIdentity.lexical(it.path) }
    val repositoryStatuses = snapshot.repositories.mapIndexed { index, resolved ->
      if (snapshot.loading != null && resolved.repository.catalogRepositoryId !in snapshot.loading.loadedIds) {
        return@mapIndexed VcsRepositoryInspection(index, VcsRepositoryStatus.NOT_LOADED)
      }
      val candidate = projectRoot.resolve(resolved.repository.relativePath).normalize()
      val lexicalIdentity = VcsPathIdentity.lexical(candidate)
      memberIdentities.add(lexicalIdentity)
      val liveObservation = if (resolved.availability == RepositoryAvailability.MISSING) {
        null
      } else {
        observeLiveRepository(projectRoot, candidate)
      }
      liveObservation?.identities?.let(memberIdentities::addAll)
      val status = when {
        resolved.availability == RepositoryAvailability.MISSING ->
          VcsRepositoryStatus.MISSING_DIRECTORY
        liveObservation == null ->
          VcsRepositoryStatus.MISSING_DIRECTORY
        !liveObservation.isGitRepository -> VcsRepositoryStatus.NOT_GIT
        !gitAvailable -> VcsRepositoryStatus.NOT_CONFIGURED
        else -> classifyConfiguredRepository(liveObservation.identities, mappingObservations)
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
        observation.identities.none { it in memberIdentities }
      ) {
        workspaceDiagnostics.add(VcsWorkspaceDiagnosticCode.EXTRA_GIT_ROOT)
      }
    }

    return VcsRootInspection(
      repositoryStatuses = repositoryStatuses,
      workspaceDiagnostics = workspaceDiagnostics.toList(),
    )
  }

  private fun observeLiveRepository(
    projectRoot: Path,
    candidate: Path,
  ): LiveRepositoryObservation? {
    if (!Files.exists(candidate, LinkOption.NOFOLLOW_LINKS)) return null

    val liveCanonicalPath = candidate.toRealPath()
    val isGitRepository = liveCanonicalPath != projectRoot &&
      liveCanonicalPath.startsWith(projectRoot) &&
      Files.isDirectory(liveCanonicalPath, LinkOption.NOFOLLOW_LINKS) &&
      Files.isDirectory(liveCanonicalPath.resolve(".git"), LinkOption.NOFOLLOW_LINKS)
    return LiveRepositoryObservation(
      identities = VcsPathIdentity.repositoryIdentities(candidate, liveCanonicalPath),
      isGitRepository = isGitRepository,
    )
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

private data class LiveRepositoryObservation(
  val identities: Set<String>,
  val isGitRepository: Boolean,
)
