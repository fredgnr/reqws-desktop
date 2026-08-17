package com.reqws.goland.project

import com.reqws.goland.manifest.ManifestSnapshot

enum class ReqwsLifecycleState {
  INACTIVE,
  READING,
  SAFE_MODE_BLOCKED,
  SYNCHRONIZING,
  SYNCHRONIZED,
  DEGRADED,
  ERROR,
  DISPOSED,
}

data class ReqwsProjectError(
  val code: String,
  val field: String? = null,
  val digestSha256: String? = null,
) {
  override fun toString(): String =
    "ReqwsProjectError(code=$code, field=$field, digestSha256=$digestSha256)"
}

data class ReqwsProjectState(
  val lifecycle: ReqwsLifecycleState,
  val snapshot: ManifestSnapshot? = null,
  val lastAppliedDigest: String? = null,
  val lastError: ReqwsProjectError? = null,
) {
  companion object {
    val INACTIVE = ReqwsProjectState(ReqwsLifecycleState.INACTIVE)
    val DISPOSED = ReqwsProjectState(ReqwsLifecycleState.DISPOSED)
  }
}
