package com.reqws.goland.project

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ReqwsProjectStateTest {
  @Test
  fun `successful projection records transient proof for the current service`() {
    val digest = "d".repeat(64)

    val projected = ReqwsProjectState(
      lifecycle = ReqwsLifecycleState.SYNCHRONIZING,
      lastAppliedDigest = "a".repeat(64),
    ).afterSuccessfulProjection(digest)

    assertEquals(digest, projected.lastAppliedDigest)
    assertEquals(digest, projected.validatedProjectionDigest)
  }

  @Test
  fun `persisted digest alone does not initialize transient projection proof`() {
    val restored = ReqwsProjectState(
      lifecycle = ReqwsLifecycleState.SAFE_MODE_BLOCKED,
      lastAppliedDigest = "a".repeat(64),
    )

    assertNull(restored.validatedProjectionDigest)
  }
}
