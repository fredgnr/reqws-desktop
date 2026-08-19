package com.reqws.goland.project

import com.intellij.ide.trustedProjects.TrustedProjects
import com.intellij.openapi.components.service
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.project.Project
import com.reqws.goland.manifest.ManifestSnapshot
import com.reqws.goland.projectmodel.ProjectModelApplyException
import com.reqws.goland.projectmodel.ProjectModelErrorCode
import com.reqws.goland.projectmodel.ReqwsProjectModelAdapter
import kotlinx.coroutines.CancellationException

internal object ReqwsStableErrorCode {
  const val PROJECT_MODEL_APPLY_FAILED = "PROJECT_MODEL_APPLY_FAILED"
  const val REFRESH_FAILED = "REFRESH_FAILED"
  const val OWNERSHIP_CONFLICT = "OWNERSHIP_CONFLICT"
  const val SAFE_MODE_BLOCKED = "SAFE_MODE_BLOCKED"
  const val VCS_CONFIGURATION_MISMATCH = "VCS_CONFIGURATION_MISMATCH"
  const val VCS_DIAGNOSTIC_FAILED = "VCS_DIAGNOSTIC_FAILED"
  const val GIT_PLUGIN_UNAVAILABLE = "GIT_PLUGIN_UNAVAILABLE"
  const val REPOSITORY_NOT_GIT = "REPOSITORY_NOT_GIT"
}

internal class ReqwsProjectionApplyException(
  val stableCode: String,
  val degraded: Boolean,
  cause: Throwable? = null,
) : RuntimeException(stableCode, cause) {
  override fun toString(): String =
    "ReqwsProjectionApplyException(stableCode=$stableCode, degraded=$degraded)"
}

internal fun interface ProjectModelProjection {
  suspend fun apply(snapshot: ManifestSnapshot)
}

internal fun interface AppliedDigestRecorder {
  fun markApplied(digestSha256: String)
}

/** Applies the managed project model and advances the digest after that owned projection converges. */
internal class ReqwsProjectionApplier(
  private val isTrusted: () -> Boolean,
  private val isProjectDisposed: () -> Boolean = { false },
  private val projectModel: ProjectModelProjection,
  private val digestRecorder: AppliedDigestRecorder,
) {
  suspend fun apply(snapshot: ManifestSnapshot) {
    ensureProjectionAllowed()

    try {
      projectModel.apply(snapshot)
    } catch (exception: ProcessCanceledException) {
      throw exception
    } catch (exception: CancellationException) {
      throw exception
    } catch (exception: ProjectModelApplyException) {
      throw mapProjectModelFailure(exception)
    } catch (exception: Exception) {
      throw ReqwsProjectionApplyException(
        stableCode = ReqwsStableErrorCode.PROJECT_MODEL_APPLY_FAILED,
        degraded = false,
        cause = exception,
      )
    }

    // The model adapter performs its own transaction gates. Recheck the service lifecycle before
    // publishing a clean digest because service disposal can precede Project.isDisposed.
    ensureProjectionAllowed()
    digestRecorder.markApplied(snapshot.digestSha256)
  }

  private fun ensureProjectionAllowed() {
    if (isProjectDisposed()) {
      throw ReqwsProjectionApplyException(
        stableCode = ReqwsStableErrorCode.PROJECT_MODEL_APPLY_FAILED,
        degraded = false,
      )
    }
    if (!isTrusted()) throw safeModeFailure()
  }

  private fun mapProjectModelFailure(exception: ProjectModelApplyException): ReqwsProjectionApplyException {
    val stableCode = when (exception.code) {
      ProjectModelErrorCode.UNTRUSTED_PROJECT -> ReqwsStableErrorCode.SAFE_MODE_BLOCKED
      ProjectModelErrorCode.INVALID_OWNERSHIP_STATE,
      ProjectModelErrorCode.NESTED_CONTENT_ROOT_CONFLICT,
      ProjectModelErrorCode.OWNERSHIP_CONFLICT -> ReqwsStableErrorCode.OWNERSHIP_CONFLICT
      else -> ReqwsStableErrorCode.PROJECT_MODEL_APPLY_FAILED
    }
    return ReqwsProjectionApplyException(
      stableCode = stableCode,
      degraded = stableCode == ReqwsStableErrorCode.OWNERSHIP_CONFLICT,
      cause = exception,
    )
  }

  private fun safeModeFailure() = ReqwsProjectionApplyException(
    stableCode = ReqwsStableErrorCode.SAFE_MODE_BLOCKED,
    degraded = false,
  )

  companion object {
    fun forProject(
      project: Project,
      isServiceDisposed: () -> Boolean = { false },
    ): ReqwsProjectionApplier {
      val persistence = project.service<ReqwsSyncPersistence>()
      val isDisposed = { project.isDisposed || isServiceDisposed() }
      return ReqwsProjectionApplier(
        isTrusted = { TrustedProjects.isProjectTrusted(project) },
        isProjectDisposed = isDisposed,
        projectModel = ProjectModelProjection { snapshot ->
          project.service<ReqwsProjectModelAdapter>().apply(snapshot, isDisposed)
        },
        digestRecorder = AppliedDigestRecorder(persistence::markApplied),
      )
    }
  }
}
