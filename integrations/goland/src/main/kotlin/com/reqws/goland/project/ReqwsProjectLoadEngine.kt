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
      val loading = com.reqws.goland.loading.contract.LoadingSnapshotReader(manifestReader).read(projectRoot)
      val snapshot = loading.manifest.copy(digestSha256 = loading.digest, loading = loading)
      val lifecycle = when {
        !trustGate.isTrusted() -> ReqwsLifecycleState.SAFE_MODE_BLOCKED
        snapshot.missingRepositoryCount > 0 -> ReqwsLifecycleState.DEGRADED
        else -> ReqwsLifecycleState.SYNCHRONIZED
      }
      ReqwsProjectState(
        lifecycle = lifecycle,
        snapshot = snapshot,
        lastAppliedDigest = previous.lastAppliedDigest,
        validatedProjectionDigest = previous.validatedProjectionDigest,
      )
    } catch (exception: com.intellij.openapi.progress.ProcessCanceledException) { throw exception
    } catch (exception: kotlinx.coroutines.CancellationException) { throw exception
    } catch (exception: Exception) {
      ReqwsProjectState(
        lifecycle = ReqwsLifecycleState.ERROR,
        snapshot = previous.snapshot,
        lastAppliedDigest = previous.lastAppliedDigest,
        validatedProjectionDigest = previous.validatedProjectionDigest,
        lastError = ReqwsProjectError(
          code = (exception as? ManifestException)?.code?.name ?: "BINDING_ERROR",
          field = (exception as? ManifestException)?.field,
          digestSha256 = (exception as? ManifestException)?.digestSha256,
        ),
      )
    }
  }
}
