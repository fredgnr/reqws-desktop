package com.reqws.goland.projectmodel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReqwsManagedModelStateTest {
  @Test
  fun `stores sorted relative ownership with opaque marker tokens`() {
    val state = ReqwsManagedModelState()

    state.replaceExternalMirror(
      moduleName = "workspace",
      managedClaims = listOf(
        DurableManagedClaim("repo-z", TOKEN_C),
        DurableManagedClaim(".reqws", TOKEN_A),
        DurableManagedClaim("repo-a", TOKEN_B),
      ),
      recoveryClaims = listOf(DurableManagedClaim("repo-old", TOKEN_D)),
    )

    assertEquals(REQWS_MODEL_STATE_VERSION, state.state.stateVersion)
    assertEquals(REQWS_MODEL_STRATEGY, state.state.strategy)
    assertEquals("workspace", state.state.targetModuleName)
    assertEquals(
      listOf(".reqws", "repo-a", "repo-z"),
      state.state.managedExcludes.map { it.relativePath },
    )
    assertEquals(
      listOf(TOKEN_A, TOKEN_B, TOKEN_C),
      state.state.managedExcludes.map { it.markerToken },
    )
    assertEquals(listOf("repo-old"), state.state.recoveryClaims.map { it.relativePath })
    assertEquals(listOf(TOKEN_D), state.state.recoveryClaims.map { it.markerToken })
    assertTrue(state.state.pendingAdds.isEmpty())
    assertTrue(state.state.pendingRemovals.isEmpty())
  }

  @Test
  fun `loads ownership through a deep copied restart state without a model digest`() {
    val persisted = ReqwsManagedModelState.Data().also { data ->
      data.targetModuleName = "workspace"
      data.managedExcludes = mutableListOf(
        persistedClaim("repo-b", TOKEN_B),
        persistedClaim(".reqws", TOKEN_A),
      )
      data.recoveryClaims = mutableListOf(persistedClaim("repo-old", TOKEN_C))
    }
    val state = ReqwsManagedModelState()

    state.loadState(persisted)
    persisted.managedExcludes.clear()

    assertEquals(
      ManagedModelOwnership(
        stateVersion = REQWS_MODEL_STATE_VERSION,
        strategy = REQWS_MODEL_STRATEGY,
        targetModuleName = "workspace",
        managedExcludes = listOf(
          ManagedExcludeOwnership("repo-b", TOKEN_B),
          ManagedExcludeOwnership(".reqws", TOKEN_A),
        ),
        pendingAdds = emptyList(),
        pendingRemovals = emptyList(),
        recoveryClaims = listOf(ManagedExcludeOwnership("repo-old", TOKEN_C)),
      ),
      state.ownership(),
    )
    assertFalse(
      ReqwsManagedModelState.Data::class.java.declaredFields.any { it.name == "lastAppliedDigest" },
    )
  }

  @Test
  fun `returned persistence state cannot mutate stored claims`() {
    val state = ReqwsManagedModelState()
    state.replaceExternalMirror(
      moduleName = "workspace",
      managedClaims = listOf(DurableManagedClaim(".reqws", TOKEN_A)),
      recoveryClaims = emptyList(),
    )

    state.state.managedExcludes.single().markerToken = TOKEN_B

    assertEquals(TOKEN_A, state.ownership().managedExcludes.single().markerToken)
  }

  @Test
  fun `retains the version three journal only for legacy migration`() {
    val original = ReqwsManagedModelState()
    original.replaceOwnership(
      moduleName = "workspace",
      managedExcludes = mapOf(".reqws" to TOKEN_A),
      pendingAdds = mapOf("repo-a" to TOKEN_B),
      pendingRemovals = mapOf("repo-z" to TOKEN_C),
    )

    val reloaded = ReqwsManagedModelState().also { it.loadState(original.state) }

    assertEquals(REQWS_LEGACY_JOURNAL_STATE_VERSION, reloaded.ownership().stateVersion)
    assertEquals(
      listOf(ManagedExcludeOwnership(".reqws", TOKEN_A)),
      reloaded.ownership().managedExcludes,
    )
    assertEquals(
      listOf(ManagedExcludeOwnership("repo-a", TOKEN_B)),
      reloaded.ownership().pendingAdds,
    )
    assertEquals(
      listOf(ManagedExcludeOwnership("repo-z", TOKEN_C)),
      reloaded.ownership().pendingRemovals,
    )
    assertTrue(reloaded.ownership().recoveryClaims.isEmpty())
  }

  private fun persistedClaim(
    relativePath: String,
    markerToken: String,
  ): ReqwsManagedModelState.PersistedManagedExclude =
    ReqwsManagedModelState.PersistedManagedExclude().also { persisted ->
      persisted.relativePath = relativePath
      persisted.markerToken = markerToken
    }

  companion object {
    private const val TOKEN_A = "11111111111111111111111111111111"
    private const val TOKEN_B = "22222222222222222222222222222222"
    private const val TOKEN_C = "33333333333333333333333333333333"
    private const val TOKEN_D = "44444444444444444444444444444444"
  }
}
