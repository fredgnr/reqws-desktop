package com.reqws.goland.projectmodel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class ReqwsManagedModelStateTest {
  @Test
  fun `stores sorted relative ownership with opaque marker tokens`() {
    val state = ReqwsManagedModelState()

    state.replaceOwnership(
      "workspace",
      mapOf(
        "repo-z" to TOKEN_C,
        ".reqws" to TOKEN_A,
        "repo-a" to TOKEN_B,
      ),
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
  }

  @Test
  fun `loads ownership through a deep copied restart state without a model digest`() {
    val persisted = ReqwsManagedModelState.Data().also { data ->
      data.targetModuleName = "workspace"
      data.managedExcludes = mutableListOf(
        persistedClaim("repo-b", TOKEN_B),
        persistedClaim(".reqws", TOKEN_A),
      )
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
    state.replaceOwnership("workspace", mapOf(".reqws" to TOKEN_A))

    state.state.managedExcludes.single().markerToken = TOKEN_B

    assertEquals(TOKEN_A, state.ownership().managedExcludes.single().markerToken)
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
  }
}
