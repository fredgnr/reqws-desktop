package com.reqws.goland.projectmodel

internal data class CurrentExclude(
  val url: String,
)

internal data class ManagedExcludeClaim(
  val targetUrl: String,
  val markerToken: String,
  val markerUrl: String,
)

internal data class ReqwsExcludePlan(
  val nextOwnership: Map<String, String>,
  val preparedOwnership: Map<String, String>,
  val preparedPendingAdds: Map<String, String>,
  val preparedPendingRemovals: Map<String, String>,
  val addedClaims: Map<String, ManagedExcludeClaim>,
  val added: Set<String>,
  val removed: Set<String>,
  val kept: Set<String>,
  val borrowed: Set<String>,
  val staleOwnership: Set<String>,
  val removableUrls: Set<String>,
)

internal object ReqwsExcludePlanner {
  fun plan(
    desiredUrls: Map<String, String>,
    activeUrls: Set<String>,
    previousClaims: Map<String, ManagedExcludeClaim>,
    pendingAddClaims: Map<String, ManagedExcludeClaim> = emptyMap(),
    pendingRemoveClaims: Map<String, ManagedExcludeClaim> = emptyMap(),
    candidateClaims: Map<String, ManagedExcludeClaim>,
    currentExcludes: List<CurrentExclude>,
    urlsEquivalent: (String, String) -> Boolean = { first, second -> first == second },
  ): ReqwsExcludePlan {
    if (
      desiredUrls.values.any { desired ->
        activeUrls.any { active -> urlsEquivalent(desired, active) }
      }
    ) {
      throw conflict("An active repository cannot be a ReqWS exclude target.")
    }
    val persistedClaims = listOf(previousClaims, pendingAddClaims, pendingRemoveClaims)
    val persistedRelativePaths = persistedClaims.flatMap { it.keys }
    if (
      persistedRelativePaths.toSet().size != persistedRelativePaths.size ||
      candidateClaims.keys.any { relative ->
        relative in previousClaims || relative in pendingAddClaims
      } ||
      persistedClaims.any { claims ->
        claims.any { (relative, claim) ->
          relative in desiredUrls && claim.targetUrl != desiredUrls[relative]
        }
      } ||
      candidateClaims.any { (relative, claim) -> claim.targetUrl != desiredUrls[relative] }
    ) {
      throw conflict("ReqWS ownership targets do not match the desired project model.")
    }
    val allClaims = previousClaims.values +
      pendingAddClaims.values +
      pendingRemoveClaims.values +
      candidateClaims.values
    if (
      allClaims.map(ManagedExcludeClaim::markerToken).toSet().size != allClaims.size ||
      allClaims.map(ManagedExcludeClaim::markerUrl).toSet().size != allClaims.size
    ) {
      throw conflict("ReqWS ownership markers must be unique.")
    }

    val relevantUrls = buildSet {
      addAll(desiredUrls.values)
      addAll(activeUrls)
      allClaims.forEach { claim ->
        add(claim.targetUrl)
        add(claim.markerUrl)
      }
    }
    if (
      relevantUrls.any { relevant ->
        currentExcludes.count { current -> urlsEquivalent(current.url, relevant) } > 1
      }
    ) {
      throw conflict("Duplicate exclude URLs make ReqWS ownership ambiguous.")
    }

    val previousProofs = previousClaims.mapValues { (_, claim) ->
      proofFor(claim, currentExcludes, urlsEquivalent)
    }
    val pendingAddProofs = pendingAddClaims.mapValues { (_, claim) ->
      proofFor(claim, currentExcludes, urlsEquivalent)
    }
    val pendingRemoveProofs = pendingRemoveClaims.mapValues { (_, claim) ->
      proofFor(claim, currentExcludes, urlsEquivalent)
    }
    previousProofs.forEach { (_, proof) ->
      if (proof !is ClaimProof.Complete) {
        throw conflict("A previously managed exclude no longer has both ownership proofs.")
      }
    }

    val nextOwnership = linkedMapOf<String, String>()
    val preparedOwnership = linkedMapOf<String, String>()
    val preparedPendingAdds = linkedMapOf<String, String>()
    val preparedPendingRemovals = linkedMapOf<String, String>()
    val addedClaims = linkedMapOf<String, ManagedExcludeClaim>()
    val added = linkedSetOf<String>()
    val removed = linkedSetOf<String>()
    val kept = linkedSetOf<String>()
    val borrowed = linkedSetOf<String>()
    val removableUrls = linkedSetOf<String>()

    fun scheduleAdd(relative: String, claim: ManagedExcludeClaim) {
      preparedPendingAdds[relative] = claim.markerToken
      nextOwnership[relative] = claim.markerToken
      addedClaims[relative] = claim
      added.add(relative)
    }

    fun scheduleRemove(
      relative: String,
      markerToken: String,
      targetUrl: String,
      markerUrl: String,
    ) {
      preparedPendingRemovals[relative] = markerToken
      removed.add(relative)
      removableUrls.add(targetUrl)
      removableUrls.add(markerUrl)
    }

    previousClaims.forEach { (relative, claim) ->
      val proof = previousProofs.getValue(relative) as ClaimProof.Complete
      if (relative in desiredUrls) {
        preparedOwnership[relative] = claim.markerToken
        nextOwnership[relative] = claim.markerToken
        kept.add(relative)
      } else {
        scheduleRemove(relative, claim.markerToken, proof.targetUrl, proof.markerUrl)
      }
    }
    pendingAddClaims.forEach { (relative, claim) ->
      when (val proof = pendingAddProofs.getValue(relative)) {
        is ClaimProof.Complete -> if (relative in desiredUrls) {
          preparedPendingAdds[relative] = claim.markerToken
          nextOwnership[relative] = claim.markerToken
          kept.add(relative)
        } else {
          scheduleRemove(relative, claim.markerToken, proof.targetUrl, proof.markerUrl)
        }
        ClaimProof.Absent -> if (relative in desiredUrls) scheduleAdd(relative, claim)
      }
    }
    pendingRemoveClaims.forEach { (relative, claim) ->
      when (val proof = pendingRemoveProofs.getValue(relative)) {
        is ClaimProof.Complete -> if (relative in desiredUrls) {
          preparedPendingRemovals[relative] = claim.markerToken
          nextOwnership[relative] = claim.markerToken
          kept.add(relative)
        } else {
          scheduleRemove(relative, claim.markerToken, proof.targetUrl, proof.markerUrl)
        }
        ClaimProof.Absent -> if (relative in desiredUrls) {
          val replacement = candidateClaims[relative]
            ?: throw conflict("A restarted ReqWS exclude has no fresh ownership marker candidate.")
          scheduleAdd(relative, replacement)
        }
      }
    }

    val remainingExcludes = currentExcludes.filter { it.url !in removableUrls }
    if (
      activeUrls.any { active ->
        remainingExcludes.any { current -> urlsEquivalent(current.url, active) }
      }
    ) {
      throw conflict("An active repository remains excluded by an entry ReqWS cannot remove.")
    }

    desiredUrls.forEach { (relative, targetUrl) ->
      if (persistedClaims.any { relative in it }) {
        return@forEach
      }

      if (remainingExcludes.any { current -> urlsEquivalent(current.url, targetUrl) }) {
        borrowed.add(relative)
        return@forEach
      }

      val candidate = candidateClaims[relative]
        ?: throw conflict("A new ReqWS exclude has no ownership marker candidate.")
      if (remainingExcludes.any { current -> urlsEquivalent(current.url, candidate.markerUrl) }) {
        throw conflict("A new ReqWS ownership marker collides with an existing exclude.")
      }
      scheduleAdd(relative, candidate)
    }

    return ReqwsExcludePlan(
      nextOwnership = nextOwnership,
      preparedOwnership = preparedOwnership,
      preparedPendingAdds = preparedPendingAdds,
      preparedPendingRemovals = preparedPendingRemovals,
      addedClaims = addedClaims,
      added = added,
      removed = removed,
      kept = kept,
      borrowed = borrowed,
      staleOwnership = emptySet(),
      removableUrls = removableUrls,
    )
  }

  private fun proofFor(
    claim: ManagedExcludeClaim,
    currentExcludes: List<CurrentExclude>,
    urlsEquivalent: (String, String) -> Boolean,
  ): ClaimProof {
    val targetMatches = currentExcludes.filter { current ->
      urlsEquivalent(current.url, claim.targetUrl)
    }
    val markerMatches = currentExcludes.filter { current ->
      urlsEquivalent(current.url, claim.markerUrl)
    }
    return when {
      targetMatches.isEmpty() && markerMatches.isEmpty() -> ClaimProof.Absent
      targetMatches.size == 1 && markerMatches.size == 1 -> ClaimProof.Complete(
        targetUrl = targetMatches.single().url,
        markerUrl = markerMatches.single().url,
      )
      else -> throw conflict(
        "A ReqWS exclude does not have an atomic target and ownership marker pair.",
      )
    }
  }

  private fun conflict(message: String) =
    ProjectModelApplyException(ProjectModelErrorCode.OWNERSHIP_CONFLICT, message)

  private sealed interface ClaimProof {
    data object Absent : ClaimProof

    data class Complete(
      val targetUrl: String,
      val markerUrl: String,
    ) : ClaimProof
  }
}
