package com.reqws.goland.project

import com.reqws.goland.manifest.ManifestException
import com.reqws.goland.manifest.ManifestReader
import java.nio.file.Path

internal fun interface ReqwsTrustGate {
  fun isTrusted(): Boolean
}

/** Pure state transition used by the project service and focused unit tests. */
internal class ReqwsProjectLoadEngine(
  private val manifestReader: ManifestReader,
  private val trustGate: ReqwsTrustGate,
) {
  fun load(projectRoot: Path?, previous: ReqwsProjectState): ReqwsProjectState {
    if (projectRoot == null) {
      return ReqwsProjectState.INACTIVE
    }
    if (ReqwsProjectDetector.detect(projectRoot) == null && previous.snapshot == null) {
      return ReqwsProjectState.INACTIVE
    }

    return try {
      val snapshot = manifestReader.read(projectRoot)
      val lifecycle = when {
        !trustGate.isTrusted() -> ReqwsLifecycleState.SAFE_MODE_BLOCKED
        snapshot.missingRepositoryCount > 0 -> ReqwsLifecycleState.DEGRADED
        else -> ReqwsLifecycleState.SYNCHRONIZED
      }
      ReqwsProjectState(
        lifecycle = lifecycle,
        snapshot = snapshot,
        lastAppliedDigest = previous.lastAppliedDigest,
      )
    } catch (exception: ManifestException) {
      ReqwsProjectState(
        lifecycle = ReqwsLifecycleState.ERROR,
        snapshot = previous.snapshot,
        lastAppliedDigest = previous.lastAppliedDigest,
        lastError = ReqwsProjectError(
          code = exception.code.name,
          field = exception.field,
          digestSha256 = exception.digestSha256,
        ),
      )
    }
  }
}
