package com.reqws.goland.projectmodel

internal data class CurrentExclude(
  val url: String,
)

internal data class ManagedExcludeClaim(
  val targetUrl: String,
  val markerToken: String,
  val markerUrl: String,
)

internal data class RecoveryExcludeClaim(
  val relativePath: String,
  val claim: ManagedExcludeClaim,
)

internal data class ReqwsExcludePlan(
  val nextOwnership: Map<String, String>,
  val nextRecoveryClaims: List<ManagedExcludeOwnership>,
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
    managedClaims: Map<String, ManagedExcludeClaim>,
    recoveryClaims: List<RecoveryExcludeClaim>,
    candidateClaims: Map<String, ManagedExcludeClaim>,
    currentExcludes: List<CurrentExclude>,
    markerNamespaceUrlPrefix: String,
    canCompactRecoveryClaims: Boolean,
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
      candidateClaims.keys.any { relative -> relative !in desiredUrls || relative in managedClaims } ||
      candidateClaims.any { (relative, claim) -> claim.targetUrl != desiredUrls[relative] }
    ) {
      throw conflict("ReqWS candidate ownership does not match the desired project model.")
    }

    val allPersistedClaims = buildList {
      managedClaims.forEach { (relative, claim) -> add(RecoveryExcludeClaim(relative, claim)) }
      addAll(recoveryClaims)
    }
    val persistedKeys = hashSetOf<Pair<String, String>>()
    val allClaims = allPersistedClaims.map(RecoveryExcludeClaim::claim) + candidateClaims.values
    if (
      allPersistedClaims.any { persisted ->
        !persistedKeys.add(persisted.relativePath to persisted.claim.markerToken)
      } ||
      allClaims.map(ManagedExcludeClaim::markerToken).toSet().size != allClaims.size ||
      allClaims.map(ManagedExcludeClaim::markerUrl).toSet().size != allClaims.size
    ) {
      throw conflict("ReqWS ownership claims and markers must be unique.")
    }
    allPersistedClaims.groupBy(RecoveryExcludeClaim::relativePath).values.forEach { claims ->
      val firstTarget = claims.first().claim.targetUrl
      if (claims.any { persisted -> persisted.claim.targetUrl != firstTarget }) {
        throw conflict("ReqWS ownership claims disagree about their target path.")
      }
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
    val knownMarkerUrls = allClaims.map(ManagedExcludeClaim::markerUrl)
    if (
      candidateClaims.values.any { candidate ->
        currentExcludes.any { current -> urlsEquivalent(current.url, candidate.markerUrl) }
      }
    ) {
      throw conflict("A fresh ReqWS ownership marker already exists in the project model.")
    }
    if (
      currentExcludes.any { current ->
        current.url.startsWith(markerNamespaceUrlPrefix) &&
          knownMarkerUrls.none { known -> urlsEquivalent(current.url, known) }
      }
    ) {
      throw conflict("The project model contains an unclaimed ReqWS ownership marker.")
    }

    val persistedByRelative = allPersistedClaims.groupBy(RecoveryExcludeClaim::relativePath)
    val proofs = persistedByRelative.mapValues { (_, claims) ->
      proofFor(claims, currentExcludes, urlsEquivalent)
    }
    val retainedRecovery = linkedMapOf<Pair<String, String>, ManagedExcludeOwnership>()
    recoveryClaims.forEach { persisted ->
      val proof = proofs.getValue(persisted.relativePath)
      val isPresentClaim = proof is RelativeProof.Owned &&
        proof.persisted.claim.markerToken == persisted.claim.markerToken
      if (!canCompactRecoveryClaims || isPresentClaim) {
        retainedRecovery[persisted.relativePath to persisted.claim.markerToken] =
          ManagedExcludeOwnership(persisted.relativePath, persisted.claim.markerToken)
      }
    }

    val nextOwnership = linkedMapOf<String, String>()
    val addedClaims = linkedMapOf<String, ManagedExcludeClaim>()
    val added = linkedSetOf<String>()
    val removed = linkedSetOf<String>()
    val kept = linkedSetOf<String>()
    val borrowed = linkedSetOf<String>()
    val removableUrls = linkedSetOf<String>()

    fun retainRecovery(relative: String, claim: ManagedExcludeClaim) {
      retainedRecovery[relative to claim.markerToken] =
        ManagedExcludeOwnership(relative, claim.markerToken)
    }

    fun scheduleRemove(relative: String, proof: RelativeProof.Owned) {
      retainRecovery(relative, proof.persisted.claim)
      removed.add(relative)
      removableUrls.add(proof.targetUrl)
      removableUrls.add(proof.markerUrl)
    }

    fun scheduleAdd(relative: String, claim: ManagedExcludeClaim) {
      nextOwnership[relative] = claim.markerToken
      addedClaims[relative] = claim
      added.add(relative)
    }

    val allRelativePaths = buildSet {
      addAll(desiredUrls.keys)
      addAll(managedClaims.keys)
      addAll(recoveryClaims.map(RecoveryExcludeClaim::relativePath))
    }
    allRelativePaths.sorted().forEach { relative ->
      val desired = relative in desiredUrls
      val managed = managedClaims[relative]
      val proof = proofs[relative] ?: proofWithoutPersistedClaims(
        targetUrl = desiredUrls[relative],
        currentExcludes = currentExcludes,
        urlsEquivalent = urlsEquivalent,
      )
      if (desired) {
        when (proof) {
          RelativeProof.Absent -> {
            val claim = managed ?: candidateClaims[relative]
              ?: throw conflict("A desired ReqWS exclude has no ownership marker candidate.")
            scheduleAdd(relative, claim)
          }
          is RelativeProof.Borrowed -> {
            if (managed != null || recoveryClaims.any { it.relativePath == relative }) {
              throw conflict("A claimed ReqWS target lost its ownership marker.")
            }
            borrowed.add(relative)
          }
          is RelativeProof.Owned -> {
            if (managed?.markerToken == proof.persisted.claim.markerToken) {
              nextOwnership[relative] = managed.markerToken
              kept.add(relative)
            } else {
              val replacement = managed ?: candidateClaims[relative]
                ?: throw conflict("A re-added ReqWS exclude has no fresh ownership marker candidate.")
              scheduleRemove(relative, proof)
              scheduleAdd(relative, replacement)
            }
          }
        }
      } else {
        if (managed != null) {
          val managedIsPresent = proof is RelativeProof.Owned &&
            proof.persisted.claim.markerToken == managed.markerToken
          if (!canCompactRecoveryClaims || managedIsPresent) retainRecovery(relative, managed)
        }
        if (proof is RelativeProof.Owned) scheduleRemove(relative, proof)
      }
    }

    val remainingExcludes = currentExcludes.filter { current -> current.url !in removableUrls }
    if (
      activeUrls.any { active ->
        remainingExcludes.any { current -> urlsEquivalent(current.url, active) }
      }
    ) {
      throw conflict("An active repository remains excluded by an entry ReqWS cannot remove.")
    }

    return ReqwsExcludePlan(
      nextOwnership = nextOwnership,
      nextRecoveryClaims = retainedRecovery.values.sortedWith(
        compareBy(ManagedExcludeOwnership::relativePath, ManagedExcludeOwnership::markerToken),
      ),
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
    persistedClaims: List<RecoveryExcludeClaim>,
    currentExcludes: List<CurrentExclude>,
    urlsEquivalent: (String, String) -> Boolean,
  ): RelativeProof {
    val targetUrl = persistedClaims.first().claim.targetUrl
    val targetMatches = currentExcludes.filter { current ->
      urlsEquivalent(current.url, targetUrl)
    }
    val presentMarkers = persistedClaims.mapNotNull { persisted ->
      val matches = currentExcludes.filter { current ->
        urlsEquivalent(current.url, persisted.claim.markerUrl)
      }
      if (matches.size > 1) throw conflict("A ReqWS ownership marker is duplicated.")
      matches.singleOrNull()?.let { current -> persisted to current.url }
    }
    return when {
      targetMatches.size > 1 || presentMarkers.size > 1 ->
        throw conflict("A ReqWS exclude has ambiguous ownership proof.")
      targetMatches.isEmpty() && presentMarkers.isEmpty() -> RelativeProof.Absent
      targetMatches.size == 1 && presentMarkers.size == 1 -> RelativeProof.Owned(
        persisted = presentMarkers.single().first,
        targetUrl = targetMatches.single().url,
        markerUrl = presentMarkers.single().second,
      )
      else -> throw conflict("A ReqWS exclude does not have an atomic target and marker pair.")
    }
  }

  private fun proofWithoutPersistedClaims(
    targetUrl: String?,
    currentExcludes: List<CurrentExclude>,
    urlsEquivalent: (String, String) -> Boolean,
  ): RelativeProof {
    if (targetUrl == null) return RelativeProof.Absent
    val targets = currentExcludes.filter { current -> urlsEquivalent(current.url, targetUrl) }
    return when (targets.size) {
      0 -> RelativeProof.Absent
      1 -> RelativeProof.Borrowed(targets.single().url)
      else -> throw conflict("A desired exclude target is duplicated.")
    }
  }

  private fun conflict(message: String) =
    ProjectModelApplyException(ProjectModelErrorCode.OWNERSHIP_CONFLICT, message)

  private sealed interface RelativeProof {
    data object Absent : RelativeProof

    data class Borrowed(val targetUrl: String) : RelativeProof

    data class Owned(
      val persisted: RecoveryExcludeClaim,
      val targetUrl: String,
      val markerUrl: String,
    ) : RelativeProof
  }
}
