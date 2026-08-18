package com.reqws.goland.project

import com.reqws.goland.manifest.ManifestSnapshot
import com.reqws.goland.vcs.VcsRootInspection

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
  val vcsInspection: VcsRootInspection? = null,
) {
  companion object {
    val INACTIVE = ReqwsProjectState(ReqwsLifecycleState.INACTIVE)
    val DISPOSED = ReqwsProjectState(ReqwsLifecycleState.DISPOSED)
  }
}

internal fun ReqwsProjectState.afterSuccessfulProjection(
  appliedDigest: String,
): ReqwsProjectState = copy(
  lifecycle = if (
    snapshot?.missingRepositoryCount?.let { it > 0 } == true ||
    vcsInspection?.degraded == true
  ) {
    ReqwsLifecycleState.DEGRADED
  } else {
    ReqwsLifecycleState.SYNCHRONIZED
  },
  lastAppliedDigest = appliedDigest,
  lastError = null,
)
