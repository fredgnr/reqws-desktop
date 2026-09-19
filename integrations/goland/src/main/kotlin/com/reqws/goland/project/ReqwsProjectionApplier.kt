package com.reqws.goland.project

import com.intellij.ide.trustedProjects.TrustedProjects
import com.intellij.openapi.components.service
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.project.Project
import com.reqws.goland.manifest.ManifestSnapshot
import com.reqws.goland.projectmodel.ProjectModelApplyException
import com.reqws.goland.projectmodel.ProjectModelErrorCode
import com.reqws.goland.loading.model.LoadedProjectionService
import kotlinx.coroutines.CancellationException

internal object ReqwsStableErrorCode {
  const val PROJECT_MODEL_APPLY_FAILED = "PROJECT_MODEL_APPLY_FAILED"
  const val PROJECT_CONTENT_NOT_CONVERGED = "PROJECT_CONTENT_NOT_CONVERGED"
  const val REFRESH_FAILED = "REFRESH_FAILED"
  const val OWNERSHIP_CONFLICT = "OWNERSHIP_CONFLICT"
  const val SAFE_MODE_BLOCKED = "SAFE_MODE_BLOCKED"
  const val VCS_CONFIGURATION_MISMATCH = "VCS_CONFIGURATION_MISMATCH"
  const val VCS_DIAGNOSTIC_FAILED = "VCS_DIAGNOSTIC_FAILED"
  const val GIT_PLUGIN_UNAVAILABLE = "GIT_PLUGIN_UNAVAILABLE"
  const val REPOSITORY_NOT_GIT = "REPOSITORY_NOT_GIT"
}

internal enum class ReqwsProjectionRetryKind {
  PROJECT_METADATA_READINESS,
}

internal class ReqwsProjectionApplyException(
  val stableCode: String,
  val degraded: Boolean,
  val field: String? = null,
  val retryKind: ReqwsProjectionRetryKind? = null,
  cause: Throwable? = null,
) : RuntimeException(stableCode, cause) {
  override fun toString(): String =
    "ReqwsProjectionApplyException(" +
      "stableCode=$stableCode, degraded=$degraded, field=$field, retryKind=$retryKind)"
}

internal fun interface ProjectModelProjection {
  suspend fun apply(snapshot: ManifestSnapshot)
}

/** Applies and verifies the managed projection; the coordinator commits its digest afterward. */
internal class ReqwsProjectionApplier(
  private val isTrusted: () -> Boolean,
  private val isProjectDisposed: () -> Boolean = { false },
  private val projectModel: ProjectModelProjection,
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
    // returning to the coordinator's accepted-success commit because service disposal can precede
    // Project.isDisposed.
    ensureProjectionAllowed()
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
      ProjectModelErrorCode.PROJECT_METADATA_NOT_READY ->
        ReqwsStableErrorCode.PROJECT_MODEL_APPLY_FAILED
      ProjectModelErrorCode.OWNERSHIP_CONFLICT -> ReqwsStableErrorCode.OWNERSHIP_CONFLICT
      ProjectModelErrorCode.LIVE_FILE_INDEX_NOT_CONVERGED ->
        ReqwsStableErrorCode.PROJECT_CONTENT_NOT_CONVERGED
      else -> ReqwsStableErrorCode.PROJECT_MODEL_APPLY_FAILED
    }
    val field = when (exception.code) {
      ProjectModelErrorCode.LIVE_FILE_INDEX_NOT_CONVERGED -> "PROJECT_FILE_INDEX"
      else -> null
    }
    return ReqwsProjectionApplyException(
      stableCode = stableCode,
      degraded = stableCode in setOf(
        ReqwsStableErrorCode.OWNERSHIP_CONFLICT,
        ReqwsStableErrorCode.PROJECT_CONTENT_NOT_CONVERGED,
      ),
      field = field,
      retryKind = ReqwsProjectionRetryKind.PROJECT_METADATA_READINESS.takeIf {
        exception.code == ProjectModelErrorCode.PROJECT_METADATA_NOT_READY
      },
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
      isCurrent: () -> Boolean = { true },
    ): ReqwsProjectionApplier {
      val isDisposed = { project.isDisposed || isServiceDisposed() }
      return ReqwsProjectionApplier(
        isTrusted = { TrustedProjects.isProjectTrusted(project) },
        isProjectDisposed = isDisposed,
        projectModel = ProjectModelProjection { snapshot ->
          project.service<LoadedProjectionService>().apply(
            snapshot = requireNotNull(snapshot.loading) { "Only a verified shell binding can project roots." },
            current = { !isDisposed() && isCurrent() },
          )
        },
      )
    }
  }
}
