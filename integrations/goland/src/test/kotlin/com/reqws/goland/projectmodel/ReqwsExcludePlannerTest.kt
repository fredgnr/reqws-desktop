package com.reqws.goland.projectmodel

import org.junit.Assert.assertEquals
import org.junit.Test

class ReqwsExcludePlannerTest {
  @Test
  fun `plans atomic target and marker add keep remove and borrow`() {
    val reqws = claim("file:///workspace/.reqws", TOKEN_A)
    val active = claim("file:///workspace/active", TOKEN_B)
    val retained = claim("file:///workspace/retained", TOKEN_C)
    val plan = ReqwsExcludePlanner.plan(
      desiredUrls = mapOf(
        ".reqws" to reqws.targetUrl,
        "retained" to retained.targetUrl,
        "borrowed" to "file:///workspace/borrowed",
      ),
      activeUrls = setOf(active.targetUrl),
      previousClaims = mapOf(
        ".reqws" to reqws,
        "active" to active,
      ),
      candidateClaims = mapOf(
        "retained" to retained,
        "borrowed" to claim("file:///workspace/borrowed", TOKEN_D),
      ),
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
    assertEquals(emptySet<String>(), plan.staleOwnership)
    assertEquals(
      mapOf(".reqws" to TOKEN_A, "retained" to TOKEN_C),
      plan.nextOwnership,
    )
    assertEquals(mapOf(".reqws" to TOKEN_A), plan.preparedOwnership)
    assertEquals(mapOf("retained" to TOKEN_C), plan.preparedPendingAdds)
    assertEquals(mapOf("active" to TOKEN_B), plan.preparedPendingRemovals)
    assertEquals(setOf(active.targetUrl, active.markerUrl), plan.removableUrls)
  }

  @Test
  fun `recovers a committed pending add without borrowing its target`() {
    val pending = claim("file:///workspace/retained", TOKEN_A)

    val plan = ReqwsExcludePlanner.plan(
      desiredUrls = mapOf("retained" to pending.targetUrl),
      activeUrls = emptySet(),
      previousClaims = emptyMap(),
      pendingAddClaims = mapOf("retained" to pending),
      candidateClaims = emptyMap(),
      currentExcludes = listOf(
        CurrentExclude(pending.targetUrl),
        CurrentExclude(pending.markerUrl),
      ),
    )

    assertEquals(setOf("retained"), plan.kept)
    assertEquals(emptySet<String>(), plan.borrowed)
    assertEquals(mapOf("retained" to TOKEN_A), plan.nextOwnership)
    assertEquals(mapOf("retained" to TOKEN_A), plan.preparedPendingAdds)
  }

  @Test
  fun `removes a committed pending add when the repository becomes active`() {
    val pending = claim("file:///workspace/readded", TOKEN_A)

    val plan = ReqwsExcludePlanner.plan(
      desiredUrls = emptyMap(),
      activeUrls = setOf(pending.targetUrl),
      previousClaims = emptyMap(),
      pendingAddClaims = mapOf("readded" to pending),
      candidateClaims = emptyMap(),
      currentExcludes = listOf(
        CurrentExclude(pending.targetUrl),
        CurrentExclude(pending.markerUrl),
      ),
    )

    assertEquals(setOf("readded"), plan.removed)
    assertEquals(mapOf("readded" to TOKEN_A), plan.preparedPendingRemovals)
    assertEquals(emptyMap<String, String>(), plan.nextOwnership)
    assertEquals(setOf(pending.targetUrl, pending.markerUrl), plan.removableUrls)
  }

  @Test
  fun `replays an uncommitted pending add with the persisted token`() {
    val pending = claim("file:///workspace/retained", TOKEN_A)

    val plan = ReqwsExcludePlanner.plan(
      desiredUrls = mapOf("retained" to pending.targetUrl),
      activeUrls = emptySet(),
      previousClaims = emptyMap(),
      pendingAddClaims = mapOf("retained" to pending),
      candidateClaims = emptyMap(),
      currentExcludes = emptyList(),
    )

    assertEquals(setOf("retained"), plan.added)
    assertEquals(mapOf("retained" to pending), plan.addedClaims)
    assertEquals(mapOf("retained" to TOKEN_A), plan.preparedPendingAdds)
    assertEquals(mapOf("retained" to TOKEN_A), plan.nextOwnership)
  }

  @Test
  fun `completes a committed pending removal without retaining deletion rights`() {
    val pending = claim("file:///workspace/active", TOKEN_A)

    val plan = ReqwsExcludePlanner.plan(
      desiredUrls = emptyMap(),
      activeUrls = setOf(pending.targetUrl),
      previousClaims = emptyMap(),
      pendingRemoveClaims = mapOf("active" to pending),
      candidateClaims = emptyMap(),
      currentExcludes = emptyList(),
    )

    assertEquals(emptyMap<String, String>(), plan.nextOwnership)
    assertEquals(emptyMap<String, String>(), plan.preparedPendingRemovals)
    assertEquals(emptySet<String>(), plan.removed)
  }

  @Test
  fun `restarts an absent pending removal with a fresh marker token`() {
    val removed = claim("file:///workspace/retained", TOKEN_A)
    val replacement = claim("file:///workspace/retained", TOKEN_B)

    val plan = ReqwsExcludePlanner.plan(
      desiredUrls = mapOf("retained" to removed.targetUrl),
      activeUrls = emptySet(),
      previousClaims = emptyMap(),
      pendingRemoveClaims = mapOf("retained" to removed),
      candidateClaims = mapOf("retained" to replacement),
      currentExcludes = emptyList(),
    )

    assertEquals(setOf("retained"), plan.added)
    assertEquals(mapOf("retained" to replacement), plan.addedClaims)
    assertEquals(mapOf("retained" to TOKEN_B), plan.preparedPendingAdds)
    assertEquals(mapOf("retained" to TOKEN_B), plan.nextOwnership)
  }

  @Test
  fun `restores a rolled back pending removal when the exclude is desired again`() {
    val pending = claim("file:///workspace/retained", TOKEN_A)

    val plan = ReqwsExcludePlanner.plan(
      desiredUrls = mapOf("retained" to pending.targetUrl),
      activeUrls = emptySet(),
      previousClaims = emptyMap(),
      pendingRemoveClaims = mapOf("retained" to pending),
      candidateClaims = emptyMap(),
      currentExcludes = listOf(
        CurrentExclude(pending.targetUrl),
        CurrentExclude(pending.markerUrl),
      ),
    )

    assertEquals(setOf("retained"), plan.kept)
    assertEquals(emptySet<String>(), plan.removed)
    assertEquals(mapOf("retained" to TOKEN_A), plan.preparedPendingRemovals)
    assertEquals(mapOf("retained" to TOKEN_A), plan.nextOwnership)
  }

  @Test
  fun `rejects a partial pending proof without deleting either entry`() {
    val pending = claim("file:///workspace/retained", TOKEN_A)
    val failure = expectConflict {
      ReqwsExcludePlanner.plan(
        desiredUrls = mapOf("retained" to pending.targetUrl),
        activeUrls = emptySet(),
        previousClaims = emptyMap(),
        pendingAddClaims = mapOf("retained" to pending),
        candidateClaims = emptyMap(),
        currentExcludes = listOf(CurrentExclude(pending.targetUrl)),
      )
    }

    assertEquals(ProjectModelErrorCode.OWNERSHIP_CONFLICT, failure.code)
  }

  @Test
  fun `matches pending proofs by filesystem-equivalent URLs and removes the actual entries`() {
    val pending = claim("file:///workspace/Retained", TOKEN_A)
    val actualTarget = "file:///workspace/retained"

    val plan = ReqwsExcludePlanner.plan(
      desiredUrls = emptyMap(),
      activeUrls = setOf(actualTarget),
      previousClaims = emptyMap(),
      pendingAddClaims = mapOf("Retained" to pending),
      candidateClaims = emptyMap(),
      currentExcludes = listOf(
        CurrentExclude(actualTarget),
        CurrentExclude(pending.markerUrl),
      ),
      urlsEquivalent = { first, second -> first.equals(second, ignoreCase = true) },
    )

    assertEquals(setOf("Retained"), plan.removed)
    assertEquals(setOf(actualTarget, pending.markerUrl), plan.removableUrls)
  }

  @Test
  fun `rejects a previous claim whose marker disappeared`() {
    val previous = claim("file:///workspace/changed", TOKEN_A)
    val failure = expectConflict {
      ReqwsExcludePlanner.plan(
        desiredUrls = mapOf("changed" to previous.targetUrl),
        activeUrls = emptySet(),
        previousClaims = mapOf("changed" to previous),
        candidateClaims = emptyMap(),
        currentExcludes = listOf(CurrentExclude(previous.targetUrl)),
      )
    }

    assertEquals(ProjectModelErrorCode.OWNERSHIP_CONFLICT, failure.code)
  }

  @Test
  fun `rejects a previous claim whose target disappeared`() {
    val previous = claim("file:///workspace/changed", TOKEN_A)
    val failure = expectConflict {
      ReqwsExcludePlanner.plan(
        desiredUrls = mapOf("changed" to previous.targetUrl),
        activeUrls = emptySet(),
        previousClaims = mapOf("changed" to previous),
        candidateClaims = emptyMap(),
        currentExcludes = listOf(CurrentExclude(previous.markerUrl)),
      )
    }

    assertEquals(ProjectModelErrorCode.OWNERSHIP_CONFLICT, failure.code)
  }

  @Test
  fun `rejects a borrowed exclude on an active repository`() {
    val failure = expectConflict {
      ReqwsExcludePlanner.plan(
        desiredUrls = emptyMap(),
        activeUrls = setOf("file:///workspace/active"),
        previousClaims = emptyMap(),
        candidateClaims = emptyMap(),
        currentExcludes = listOf(CurrentExclude("file:///workspace/active")),
      )
    }

    assertEquals(ProjectModelErrorCode.OWNERSHIP_CONFLICT, failure.code)
  }

  @Test
  fun `rejects a filesystem-equivalent borrowed exclude on an active repository`() {
    val failure = expectConflict {
      ReqwsExcludePlanner.plan(
        desiredUrls = emptyMap(),
        activeUrls = setOf("file:///workspace/Active"),
        previousClaims = emptyMap(),
        candidateClaims = emptyMap(),
        currentExcludes = listOf(CurrentExclude("file:///workspace/active")),
        urlsEquivalent = { first, second -> first.equals(second, ignoreCase = true) },
      )
    }

    assertEquals(ProjectModelErrorCode.OWNERSHIP_CONFLICT, failure.code)
  }

  @Test
  fun `borrows a filesystem-equivalent retained target without adding a duplicate`() {
    val retained = claim("file:///workspace/Retained", TOKEN_A)

    val plan = ReqwsExcludePlanner.plan(
      desiredUrls = mapOf("Retained" to retained.targetUrl),
      activeUrls = emptySet(),
      previousClaims = emptyMap(),
      candidateClaims = mapOf("Retained" to retained),
      currentExcludes = listOf(CurrentExclude("file:///workspace/retained")),
      urlsEquivalent = { first, second -> first.equals(second, ignoreCase = true) },
    )

    assertEquals(setOf("Retained"), plan.borrowed)
    assertEquals(emptySet<String>(), plan.added)
    assertEquals(emptyMap<String, String>(), plan.nextOwnership)
  }

  @Test
  fun `rejects duplicate relevant targets`() {
    val retained = claim("file:///workspace/retained", TOKEN_A)
    val failure = expectConflict {
      ReqwsExcludePlanner.plan(
        desiredUrls = mapOf("retained" to retained.targetUrl),
        activeUrls = emptySet(),
        previousClaims = emptyMap(),
        candidateClaims = mapOf("retained" to retained),
        currentExcludes = listOf(
          CurrentExclude(retained.targetUrl),
          CurrentExclude(retained.targetUrl),
        ),
      )
    }

    assertEquals(ProjectModelErrorCode.OWNERSHIP_CONFLICT, failure.code)
  }

  @Test
  fun `rejects duplicate previous markers`() {
    val previous = claim("file:///workspace/retained", TOKEN_A)
    val failure = expectConflict {
      ReqwsExcludePlanner.plan(
        desiredUrls = mapOf("retained" to previous.targetUrl),
        activeUrls = emptySet(),
        previousClaims = mapOf("retained" to previous),
        candidateClaims = emptyMap(),
        currentExcludes = listOf(
          CurrentExclude(previous.targetUrl),
          CurrentExclude(previous.markerUrl),
          CurrentExclude(previous.markerUrl),
        ),
      )
    }

    assertEquals(ProjectModelErrorCode.OWNERSHIP_CONFLICT, failure.code)
  }

  @Test
  fun `rejects a new marker colliding with an unrelated exclude`() {
    val retained = claim("file:///workspace/retained", TOKEN_A)
    val failure = expectConflict {
      ReqwsExcludePlanner.plan(
        desiredUrls = mapOf("retained" to retained.targetUrl),
        activeUrls = emptySet(),
        previousClaims = emptyMap(),
        candidateClaims = mapOf("retained" to retained),
        currentExcludes = listOf(CurrentExclude(retained.markerUrl)),
      )
    }

    assertEquals(ProjectModelErrorCode.OWNERSHIP_CONFLICT, failure.code)
  }

  @Test
  fun `rejects duplicate marker claims before planning mutations`() {
    val first = claim("file:///workspace/first", TOKEN_A)
    val second = claim("file:///workspace/second", TOKEN_A)
    val failure = expectConflict {
      ReqwsExcludePlanner.plan(
        desiredUrls = mapOf(
          "first" to first.targetUrl,
          "second" to second.targetUrl,
        ),
        activeUrls = emptySet(),
        previousClaims = emptyMap(),
        candidateClaims = mapOf("first" to first, "second" to second),
        currentExcludes = emptyList(),
      )
    }

    assertEquals(ProjectModelErrorCode.OWNERSHIP_CONFLICT, failure.code)
  }

  private fun claim(targetUrl: String, token: String) = ManagedExcludeClaim(
    targetUrl = targetUrl,
    markerToken = token,
    markerUrl = "file:///workspace/.reqws/.goland-ownership/$token",
  )

  private fun expectConflict(block: () -> Unit): ProjectModelApplyException {
    try {
      block()
    } catch (exception: ProjectModelApplyException) {
      return exception
    }
    throw AssertionError("Expected ProjectModelApplyException")
  }

  companion object {
    private const val TOKEN_A = "11111111111111111111111111111111"
    private const val TOKEN_B = "22222222222222222222222222222222"
    private const val TOKEN_C = "33333333333333333333333333333333"
    private const val TOKEN_D = "44444444444444444444444444444444"
  }
}
