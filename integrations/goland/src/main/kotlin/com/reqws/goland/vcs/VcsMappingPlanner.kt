package com.reqws.goland.vcs

import java.nio.file.Path

internal class VcsMappingPlanner {
  fun plan(
    projectRoot: Path,
    currentMappings: List<CurrentVcsMapping>,
    currentOwnership: List<VcsMappingOwnership>,
    desiredRoots: List<DesiredVcsRoot>,
  ): VcsMappingPlan {
    val additions = ArrayList<VcsMappingAddition>()
    val removals = linkedSetOf<Int>()
    val diagnostics = ArrayList<VcsMappingDiagnostic>()
    val nextOwnership = linkedMapOf<String, VcsMappingOwnership>()
    val normalizedRoot = projectRoot.toAbsolutePath().normalize()

    val ownedByIdentity = linkedMapOf<String, ResolvedOwnership>()
    val conflictedOwnershipIdentities = hashSetOf<String>()
    currentOwnership.forEach { ownership ->
      val ownedPath = VcsPathIdentity.resolveOwned(normalizedRoot, ownership.relativeDirectory)
      if (ownedPath == null) {
        diagnostics.add(VcsMappingDiagnostic(VcsMappingDiagnosticCode.OWNERSHIP_CONFLICT))
      } else if (ownedPath.lexicalIdentity in conflictedOwnershipIdentities) {
        diagnostics.add(VcsMappingDiagnostic(VcsMappingDiagnosticCode.OWNERSHIP_CONFLICT))
      } else if (ownedByIdentity.containsKey(ownedPath.lexicalIdentity)) {
        // Duplicate state makes the original record uncertain too. Retaining either record could
        // incorrectly authorize a destructive removal.
        ownedByIdentity.remove(ownedPath.lexicalIdentity)
        conflictedOwnershipIdentities.add(ownedPath.lexicalIdentity)
        diagnostics.add(VcsMappingDiagnostic(VcsMappingDiagnosticCode.OWNERSHIP_CONFLICT))
      } else {
        ownedByIdentity[ownedPath.lexicalIdentity] = ResolvedOwnership(ownership, ownedPath)
      }
    }

    val activeIdentities = linkedSetOf<String>()
    desiredRoots.forEach { desired ->
      val desiredOwnedPath = VcsPathIdentity.resolveOwned(normalizedRoot, desired.relativeDirectory)
      if (desiredOwnedPath == null) {
        diagnostics.add(
          VcsMappingDiagnostic(VcsMappingDiagnosticCode.OWNERSHIP_CONFLICT, desired.repositoryIndex),
        )
        return@forEach
      }
      val desiredIdentity = desiredOwnedPath.lexicalIdentity
      if (!activeIdentities.add(desiredIdentity)) {
        diagnostics.add(
          VcsMappingDiagnostic(VcsMappingDiagnosticCode.DUPLICATE_MAPPING, desired.repositoryIndex),
        )
        return@forEach
      }

      when (desired.availability) {
        DesiredVcsRootAvailability.MISSING -> {
          diagnostics.add(
            VcsMappingDiagnostic(
              VcsMappingDiagnosticCode.REPOSITORY_MISSING,
              desired.repositoryIndex,
            ),
          )
          preserveUnavailableOwnership(
            desired = desired,
            desiredPath = desiredOwnedPath,
            currentMappings = currentMappings,
            owned = ownedByIdentity.remove(desiredIdentity),
            nextOwnership = nextOwnership,
            diagnostics = diagnostics,
          )
          return@forEach
        }
        DesiredVcsRootAvailability.NOT_GIT_REPOSITORY -> {
          diagnostics.add(
            VcsMappingDiagnostic(
              VcsMappingDiagnosticCode.REPOSITORY_NOT_GIT,
              desired.repositoryIndex,
            ),
          )
          preserveUnavailableOwnership(
            desired = desired,
            desiredPath = desiredOwnedPath,
            currentMappings = currentMappings,
            owned = ownedByIdentity.remove(desiredIdentity),
            nextOwnership = nextOwnership,
            diagnostics = diagnostics,
          )
          return@forEach
        }
        DesiredVcsRootAvailability.PRESENT_GIT -> Unit
      }

      val directory = desired.directory ?: run {
        diagnostics.add(
          VcsMappingDiagnostic(VcsMappingDiagnosticCode.OWNERSHIP_CONFLICT, desired.repositoryIndex),
        )
        return@forEach
      }
      val directoryIdentity = VcsPathIdentity.mappingLexical(normalizedRoot, directory) ?: run {
        diagnostics.add(
          VcsMappingDiagnostic(VcsMappingDiagnosticCode.OWNERSHIP_CONFLICT, desired.repositoryIndex),
        )
        return@forEach
      }
      if (desiredOwnedPath.lexicalIdentity != directoryIdentity) {
        diagnostics.add(
          VcsMappingDiagnostic(VcsMappingDiagnosticCode.OWNERSHIP_CONFLICT, desired.repositoryIndex),
        )
        return@forEach
      }

      val equivalentMappings = currentMappings.filter { it.matches(desiredIdentity) }
      val gitMappings = equivalentMappings.filter { it.vcs == GIT_VCS_NAME }
      val owned = ownedByIdentity.remove(desiredIdentity)
      when {
        gitMappings.isNotEmpty() -> {
          val isExactCreated = owned?.ownership?.kind == VcsMappingOwnershipKind.CREATED &&
            equivalentMappings.size == 1 &&
            gitMappings.size == 1 &&
            !gitMappings.single().hasRootSettings &&
            VcsPathIdentity.sameStoredDirectory(
              gitMappings.single().directory,
              owned.path.directory,
            )
          if (equivalentMappings.size > 1) {
            diagnostics.add(
              VcsMappingDiagnostic(VcsMappingDiagnosticCode.DUPLICATE_MAPPING, desired.repositoryIndex),
            )
          }
          if (owned?.ownership?.kind == VcsMappingOwnershipKind.CREATED && !isExactCreated) {
            diagnostics.add(
              VcsMappingDiagnostic(VcsMappingDiagnosticCode.OWNERSHIP_CONFLICT, desired.repositoryIndex),
            )
          }
          val kind = if (isExactCreated) {
            VcsMappingOwnershipKind.CREATED
          } else {
            VcsMappingOwnershipKind.BORROWED
          }
          nextOwnership[desiredIdentity] = VcsMappingOwnership(
            relativeDirectory = desiredOwnedPath.relativeDirectory,
            kind = kind,
          )
        }
        equivalentMappings.isNotEmpty() -> {
          diagnostics.add(
            VcsMappingDiagnostic(VcsMappingDiagnosticCode.OWNERSHIP_CONFLICT, desired.repositoryIndex),
          )
        }
        else -> {
          additions.add(
            VcsMappingAddition(
              directory = desiredOwnedPath.directory,
              relativeDirectory = desiredOwnedPath.relativeDirectory,
            ),
          )
          nextOwnership[desiredIdentity] = VcsMappingOwnership(
            relativeDirectory = desiredOwnedPath.relativeDirectory,
            kind = VcsMappingOwnershipKind.CREATED,
          )
        }
      }
    }

    ownedByIdentity.values.forEach { owned ->
      if (owned.ownership.kind == VcsMappingOwnershipKind.BORROWED) return@forEach
      val equivalentMappings = currentMappings.filter { it.matches(owned.path.lexicalIdentity) }
      val exactGit = equivalentMappings.filter {
        it.vcs == GIT_VCS_NAME &&
          !it.hasRootSettings &&
          VcsPathIdentity.sameStoredDirectory(it.directory, owned.path.directory)
      }
      when {
        equivalentMappings.isEmpty() -> Unit
        equivalentMappings.size == 1 && exactGit.size == 1 -> removals.add(exactGit.single().index)
        else -> diagnostics.add(VcsMappingDiagnostic(VcsMappingDiagnosticCode.OWNERSHIP_CONFLICT))
      }
    }

    val rootIdentity = VcsPathIdentity.lexical(normalizedRoot)
    val identityRoot = Path.of(rootIdentity)
    currentMappings.forEach { mapping ->
      if (mapping.vcs != GIT_VCS_NAME || mapping.index in removals) return@forEach
      val identities = buildSet {
        mapping.lexicalIdentity?.let(::add)
        mapping.canonicalIdentity?.let(::add)
        if (mapping.directory.isEmpty()) add(rootIdentity)
      }
      val insideWorkspace = identities.any { identity ->
        try {
          Path.of(identity).startsWith(identityRoot)
        } catch (_: Exception) {
          false
        }
      }
      if (insideWorkspace && identities.none { it in activeIdentities }) {
        // Preserve the unknown mapping, but do not claim convergence: it can still expose a
        // retained repository through Git UI even though ReqWS has no proof that it may delete it.
        diagnostics.add(VcsMappingDiagnostic(VcsMappingDiagnosticCode.OWNERSHIP_CONFLICT))
      }
    }

    return VcsMappingPlan(
      additions = additions,
      removalIndices = removals,
      nextOwnership = nextOwnership.values.toList(),
      diagnostics = diagnostics,
    )
  }

  private fun CurrentVcsMapping.matches(identity: String): Boolean =
    lexicalIdentity == identity || canonicalIdentity == identity

  private fun preserveUnavailableOwnership(
    desired: DesiredVcsRoot,
    desiredPath: OwnedPath,
    currentMappings: List<CurrentVcsMapping>,
    owned: ResolvedOwnership?,
    nextOwnership: MutableMap<String, VcsMappingOwnership>,
    diagnostics: MutableList<VcsMappingDiagnostic>,
  ) {
    if (owned == null) return
    val equivalent = currentMappings.filter { it.matches(desiredPath.lexicalIdentity) }
    val exactGit = equivalent.filter { mapping ->
      mapping.vcs == GIT_VCS_NAME &&
        VcsPathIdentity.sameStoredDirectory(mapping.directory, owned.path.directory)
    }
    val exactUncustomizedGit = exactGit.filterNot(CurrentVcsMapping::hasRootSettings)
    val canRetain = when (owned.ownership.kind) {
      VcsMappingOwnershipKind.CREATED ->
        equivalent.size == 1 && exactUncustomizedGit.size == 1
      VcsMappingOwnershipKind.BORROWED -> exactGit.isNotEmpty()
    }
    if (canRetain) {
      nextOwnership[desiredPath.lexicalIdentity] = owned.ownership.copy(
        relativeDirectory = desiredPath.relativeDirectory,
      )
    } else if (equivalent.isNotEmpty()) {
      diagnostics.add(
        VcsMappingDiagnostic(VcsMappingDiagnosticCode.OWNERSHIP_CONFLICT, desired.repositoryIndex),
      )
    }
  }
}

private data class ResolvedOwnership(
  val ownership: VcsMappingOwnership,
  val path: OwnedPath,
)
