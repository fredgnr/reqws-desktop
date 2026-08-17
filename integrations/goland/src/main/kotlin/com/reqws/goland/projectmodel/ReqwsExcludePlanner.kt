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
    if (
      previousClaims.any { (relative, claim) ->
        claim.targetUrl != desiredUrls[relative] && relative in desiredUrls
      } ||
      candidateClaims.any { (relative, claim) -> claim.targetUrl != desiredUrls[relative] }
    ) {
      throw conflict("ReqWS ownership targets do not match the desired project model.")
    }
    val allClaims = previousClaims.values + candidateClaims.values
    if (
      allClaims.map(ManagedExcludeClaim::markerToken).toSet().size != allClaims.size ||
      allClaims.map(ManagedExcludeClaim::markerUrl).toSet().size != allClaims.size
    ) {
      throw conflict("ReqWS ownership markers must be unique.")
    }

    val currentByUrl = currentExcludes.groupBy(CurrentExclude::url)
    val relevantUrls = buildSet {
      addAll(desiredUrls.values)
      addAll(activeUrls)
      previousClaims.values.forEach { claim ->
        add(claim.targetUrl)
        add(claim.markerUrl)
      }
      candidateClaims.values.forEach { claim -> add(claim.markerUrl) }
    }
    if (
      relevantUrls.any { relevant ->
        currentExcludes.count { current -> urlsEquivalent(current.url, relevant) } > 1
      }
    ) {
      throw conflict("Duplicate exclude URLs make ReqWS ownership ambiguous.")
    }

    previousClaims.forEach { (_, claim) ->
      val targetCount = currentByUrl[claim.targetUrl].orEmpty().size
      val markerCount = currentByUrl[claim.markerUrl].orEmpty().size
      if (targetCount != 1 || markerCount != 1) {
        throw conflict("A previously managed exclude no longer has both ownership proofs.")
      }
    }

    val removed = previousClaims.keys - desiredUrls.keys
    val removableUrls = removed.flatMapTo(linkedSetOf()) { relative ->
      val claim = previousClaims.getValue(relative)
      listOf(claim.targetUrl, claim.markerUrl)
    }
    val remainingExcludes = currentExcludes.filter { it.url !in removableUrls }
    if (
      activeUrls.any { active ->
        remainingExcludes.any { current -> urlsEquivalent(current.url, active) }
      }
    ) {
      throw conflict("An active repository remains excluded by an entry ReqWS cannot remove.")
    }

    val nextOwnership = linkedMapOf<String, String>()
    val added = linkedSetOf<String>()
    val kept = linkedSetOf<String>()
    val borrowed = linkedSetOf<String>()
    desiredUrls.forEach { (relative, targetUrl) ->
      val previous = previousClaims[relative]
      if (previous != null) {
        nextOwnership[relative] = previous.markerToken
        kept.add(relative)
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
      nextOwnership[relative] = candidate.markerToken
      added.add(relative)
    }

    return ReqwsExcludePlan(
      nextOwnership = nextOwnership,
      added = added,
      removed = removed,
      kept = kept,
      borrowed = borrowed,
      staleOwnership = emptySet(),
      removableUrls = removableUrls,
    )
  }

  private fun conflict(message: String) =
    ProjectModelApplyException(ProjectModelErrorCode.OWNERSHIP_CONFLICT, message)
}
