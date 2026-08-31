package com.reqws.goland.project

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class ReqwsSyncPersistenceTest {
  @Test
  fun `round trips only a valid lowercase SHA-256 digest`() {
    val persistence = ReqwsSyncPersistence()
    val digest = "a".repeat(64)

    persistence.markApplied(digest)

    assertEquals(digest, persistence.lastAppliedDigest())
  }

  @Test
  fun `treats a tampered version or digest as untrusted state`() {
    val persistence = ReqwsSyncPersistence()
    persistence.loadState(ReqwsSyncPersistence.Data().also { state ->
      state.stateVersion = 99
      state.lastAppliedDigest = "secret-or-invalid"
    })

    assertNull(persistence.lastAppliedDigest())
  }

  @Test
  fun `rejects invalid values when advancing the applied digest`() {
    val persistence = ReqwsSyncPersistence()

    assertThrows(IllegalArgumentException::class.java) {
      persistence.markApplied("not-a-digest")
    }
  }
}
