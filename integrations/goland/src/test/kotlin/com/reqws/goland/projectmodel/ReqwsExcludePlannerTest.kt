package com.reqws.goland.projectmodel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReqwsExcludePlannerTest {
  @Test
  fun `plans add keep remove and borrow while retaining removed proof for recovery`() {
    val reqws = claim("file:///workspace/.reqws", TOKEN_A)
    val active = claim("file:///workspace/active", TOKEN_B)
    val retained = claim("file:///workspace/retained", TOKEN_C)

    val plan = plan(
      desiredUrls = mapOf(
        ".reqws" to reqws.targetUrl,
        "retained" to retained.targetUrl,
        "borrowed" to "file:///workspace/borrowed",
      ),
      activeUrls = setOf(active.targetUrl),
      managedClaims = mapOf(".reqws" to reqws, "active" to active),
      candidateClaims = mapOf("retained" to retained),
      currentExcludes = listOf(
        CurrentExclude(reqws.targetUrl),
        CurrentExclude(reqws.markerUrl),
        CurrentExclude(active.targetUrl),
        CurrentExclude(active.markerUrl),
        CurrentExclude("file:///workspace/borrowed"),
        CurrentExclude("file:///workspace/user"),
      ),
    )

    assertEquals(setOf("retained"), plan.added)
    assertEquals(setOf(".reqws"), plan.kept)
    assertEquals(setOf("active"), plan.removed)
    assertEquals(setOf("borrowed"), plan.borrowed)
    assertEquals(mapOf(".reqws" to TOKEN_A, "retained" to TOKEN_C), plan.nextOwnership)
    assertEquals(listOf(ManagedExcludeOwnership("active", TOKEN_B)), plan.nextRecoveryClaims)
    assertEquals(setOf(active.targetUrl, active.markerUrl), plan.removableUrls)
  }

  @Test
  fun `replays an absent managed claim with its persisted token`() {
    val managed = claim("file:///workspace/retained", TOKEN_A)

    val plan = plan(
      desiredUrls = mapOf("retained" to managed.targetUrl),
      managedClaims = mapOf("retained" to managed),
    )

    assertEquals(setOf("retained"), plan.added)
    assertEquals(mapOf("retained" to managed), plan.addedClaims)
    assertEquals(mapOf("retained" to TOKEN_A), plan.nextOwnership)
    assertTrue(plan.nextRecoveryClaims.isEmpty())
  }

  @Test
  fun `same JVM keeps an absent managed claim as recovery after it becomes undesired`() {
    val managed = claim("file:///workspace/removed", TOKEN_A)

    val plan = plan(
      managedClaims = mapOf("removed" to managed),
      canCompactRecoveryClaims = false,
    )

    assertEquals(emptyMap<String, String>(), plan.nextOwnership)
    assertEquals(listOf(ManagedExcludeOwnership("removed", TOKEN_A)), plan.nextRecoveryClaims)
    assertTrue(plan.removed.isEmpty())
  }

  @Test
  fun `same JVM never clears an absent recovery claim`() {
    val recovery = recovery("removed", "file:///workspace/removed", TOKEN_A)

    val plan = plan(
      recoveryClaims = listOf(recovery),
      canCompactRecoveryClaims = false,
    )

    assertEquals(listOf(ManagedExcludeOwnership("removed", TOKEN_A)), plan.nextRecoveryClaims)
  }

  @Test
  fun `new JVM cold snapshot clears only absent recovery claims`() {
    val absent = recovery("absent", "file:///workspace/absent", TOKEN_A)
    val present = recovery("present", "file:///workspace/present", TOKEN_B)

    val plan = plan(
      recoveryClaims = listOf(absent, present),
      currentExcludes = listOf(
        CurrentExclude(present.claim.targetUrl),
        CurrentExclude(present.claim.markerUrl),
      ),
      canCompactRecoveryClaims = true,
    )

    assertEquals(setOf("present"), plan.removed)
    assertEquals(listOf(ManagedExcludeOwnership("present", TOKEN_B)), plan.nextRecoveryClaims)
  }

  @Test
  fun `readd replaces a present recovery token and retains the old token`() {
    val old = recovery("retained", "file:///workspace/retained", TOKEN_A)
    val replacement = claim(old.claim.targetUrl, TOKEN_B)

    val plan = plan(
      desiredUrls = mapOf("retained" to old.claim.targetUrl),
      recoveryClaims = listOf(old),
      candidateClaims = mapOf("retained" to replacement),
      currentExcludes = listOf(
        CurrentExclude(old.claim.targetUrl),
        CurrentExclude(old.claim.markerUrl),
      ),
      canCompactRecoveryClaims = false,
    )

    assertEquals(setOf("retained"), plan.removed)
    assertEquals(setOf("retained"), plan.added)
    assertEquals(mapOf("retained" to TOKEN_B), plan.nextOwnership)
    assertEquals(listOf(ManagedExcludeOwnership("retained", TOKEN_A)), plan.nextRecoveryClaims)
    assertEquals(setOf(old.claim.targetUrl, old.claim.markerUrl), plan.removableUrls)
  }

  @Test
  fun `readd after an absent recovery claim uses a fresh token without clearing recovery`() {
    val old = recovery("retained", "file:///workspace/retained", TOKEN_A)
    val replacement = claim(old.claim.targetUrl, TOKEN_B)

    val plan = plan(
      desiredUrls = mapOf("retained" to old.claim.targetUrl),
      recoveryClaims = listOf(old),
      candidateClaims = mapOf("retained" to replacement),
      canCompactRecoveryClaims = false,
    )

    assertEquals(mapOf("retained" to TOKEN_B), plan.nextOwnership)
    assertEquals(listOf(ManagedExcludeOwnership("retained", TOKEN_A)), plan.nextRecoveryClaims)
    assertEquals(mapOf("retained" to replacement), plan.addedClaims)
  }

  @Test
  fun `rejects a partial managed proof`() {
    val managed = claim("file:///workspace/retained", TOKEN_A)

    val failure = expectConflict {
      plan(
        desiredUrls = mapOf("retained" to managed.targetUrl),
        managedClaims = mapOf("retained" to managed),
        currentExcludes = listOf(CurrentExclude(managed.targetUrl)),
      )
    }

    assertEquals(ProjectModelErrorCode.OWNERSHIP_CONFLICT, failure.code)
  }

  @Test
  fun `rejects an unclaimed marker namespace entry`() {
    val failure = expectConflict {
      plan(currentExcludes = listOf(CurrentExclude("$MARKER_PREFIX$TOKEN_A")))
    }

    assertEquals(ProjectModelErrorCode.OWNERSHIP_CONFLICT, failure.code)
  }

  @Test
  fun `rejects a candidate marker that already exists`() {
    val candidate = claim("file:///workspace/retained", TOKEN_A)

    val failure = expectConflict {
      plan(
        desiredUrls = mapOf("retained" to candidate.targetUrl),
        candidateClaims = mapOf("retained" to candidate),
        currentExcludes = listOf(CurrentExclude(candidate.markerUrl)),
      )
    }

    assertEquals(ProjectModelErrorCode.OWNERSHIP_CONFLICT, failure.code)
  }

  @Test
  fun `rejects an active borrowed exclude`() {
    val failure = expectConflict {
      plan(
        activeUrls = setOf("file:///workspace/active"),
        currentExcludes = listOf(CurrentExclude("file:///workspace/active")),
      )
    }

    assertEquals(ProjectModelErrorCode.OWNERSHIP_CONFLICT, failure.code)
  }

  @Test
  fun `removes filesystem-equivalent actual URLs`() {
    val managed = claim("file:///workspace/Retained", TOKEN_A)
    val actualTarget = "file:///workspace/retained"

    val plan = plan(
      managedClaims = mapOf("Retained" to managed),
      currentExcludes = listOf(
        CurrentExclude(actualTarget),
        CurrentExclude(managed.markerUrl),
      ),
      urlsEquivalent = { first, second -> first.equals(second, ignoreCase = true) },
    )

    assertEquals(setOf("Retained"), plan.removed)
    assertEquals(setOf(actualTarget, managed.markerUrl), plan.removableUrls)
  }

  @Test
  fun `rejects duplicate marker tokens across managed recovery and candidates`() {
    val managed = claim("file:///workspace/first", TOKEN_A)
    val prior = recovery("second", "file:///workspace/second", TOKEN_B)
    val candidate = claim("file:///workspace/third", TOKEN_B)

    val failure = expectConflict {
      plan(
        desiredUrls = mapOf("third" to candidate.targetUrl),
        managedClaims = mapOf("first" to managed),
        recoveryClaims = listOf(prior),
        candidateClaims = mapOf("third" to candidate),
      )
    }

    assertEquals(ProjectModelErrorCode.OWNERSHIP_CONFLICT, failure.code)
  }

  private fun plan(
    desiredUrls: Map<String, String> = emptyMap(),
    activeUrls: Set<String> = emptySet(),
    managedClaims: Map<String, ManagedExcludeClaim> = emptyMap(),
    recoveryClaims: List<RecoveryExcludeClaim> = emptyList(),
    candidateClaims: Map<String, ManagedExcludeClaim> = emptyMap(),
    currentExcludes: List<CurrentExclude> = emptyList(),
    canCompactRecoveryClaims: Boolean = false,
    urlsEquivalent: (String, String) -> Boolean = { first, second -> first == second },
  ) = ReqwsExcludePlanner.plan(
    desiredUrls = desiredUrls,
    activeUrls = activeUrls,
    managedClaims = managedClaims,
    recoveryClaims = recoveryClaims,
    candidateClaims = candidateClaims,
    currentExcludes = currentExcludes,
    markerNamespaceUrlPrefix = MARKER_PREFIX,
    canCompactRecoveryClaims = canCompactRecoveryClaims,
    urlsEquivalent = urlsEquivalent,
  )

  private fun claim(targetUrl: String, token: String) = ManagedExcludeClaim(
    targetUrl = targetUrl,
    markerToken = token,
    markerUrl = "$MARKER_PREFIX$token",
  )

  private fun recovery(relativePath: String, targetUrl: String, token: String) =
    RecoveryExcludeClaim(relativePath, claim(targetUrl, token))

  private fun expectConflict(block: () -> Unit): ProjectModelApplyException {
    try {
      block()
    } catch (exception: ProjectModelApplyException) {
      return exception
    }
    throw AssertionError("Expected ProjectModelApplyException")
  }

  companion object {
    private const val MARKER_PREFIX = "file:///workspace/.reqws/.goland-ownership/"
    private const val TOKEN_A = "11111111111111111111111111111111"
    private const val TOKEN_B = "22222222222222222222222222222222"
    private const val TOKEN_C = "33333333333333333333333333333333"
  }
}
