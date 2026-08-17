package com.reqws.goland.project

import com.intellij.ide.trustedProjects.TrustedProjects
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.reqws.goland.manifest.ManifestErrorCode
import com.reqws.goland.manifest.ManifestSnapshot
import com.reqws.goland.projectmodel.ProjectModelApplyException
import com.reqws.goland.projectmodel.ProjectModelErrorCode
import com.reqws.goland.projectmodel.ReqwsProjectModelAdapter
import com.reqws.goland.vcs.ReqwsVcsMappingService
import com.reqws.goland.vcs.VcsMappingApplyException
import com.reqws.goland.vcs.VcsMappingApplyErrorCode
import com.reqws.goland.vcs.VcsMappingApplyResult
import com.reqws.goland.vcs.VcsMappingDiagnosticCode

internal object ReqwsStableErrorCode {
  const val PROJECT_MODEL_APPLY_FAILED = "PROJECT_MODEL_APPLY_FAILED"
  const val VCS_MAPPING_APPLY_FAILED = "VCS_MAPPING_APPLY_FAILED"
  const val OWNERSHIP_CONFLICT = "OWNERSHIP_CONFLICT"
  const val SAFE_MODE_BLOCKED = "SAFE_MODE_BLOCKED"
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

internal fun interface VcsMappingProjection {
  suspend fun apply(snapshot: ManifestSnapshot): VcsMappingApplyResult
}

internal fun interface AppliedDigestRecorder {
  fun markApplied(digestSha256: String)
}

/** Applies both projection layers and advances the overall digest only after both fully converge. */
internal class ReqwsProjectionApplier(
  private val isTrusted: () -> Boolean,
  private val projectModel: ProjectModelProjection,
  private val vcsMappings: VcsMappingProjection,
  private val digestRecorder: AppliedDigestRecorder,
) {
  suspend fun apply(snapshot: ManifestSnapshot) {
    if (!isTrusted()) throw safeModeFailure()

    try {
      projectModel.apply(snapshot)
    } catch (exception: ProjectModelApplyException) {
      throw mapProjectModelFailure(exception)
    } catch (exception: Exception) {
      throw ReqwsProjectionApplyException(
        stableCode = ReqwsStableErrorCode.PROJECT_MODEL_APPLY_FAILED,
        degraded = false,
        cause = exception,
      )
    }

    // Trust is checked again before the independently committed VCS layer. The model adapter
    // performs its own pre-transaction gate as well.
    if (!isTrusted()) throw safeModeFailure()

    val vcsResult = try {
      vcsMappings.apply(snapshot)
    } catch (exception: VcsMappingApplyException) {
      if (exception.code == VcsMappingApplyErrorCode.SAFE_MODE_BLOCKED) {
        throw safeModeFailure()
      }
      throw ReqwsProjectionApplyException(
        stableCode = ReqwsStableErrorCode.VCS_MAPPING_APPLY_FAILED,
        degraded = true,
        cause = exception,
      )
    } catch (exception: Exception) {
      throw ReqwsProjectionApplyException(
        stableCode = ReqwsStableErrorCode.VCS_MAPPING_APPLY_FAILED,
        degraded = true,
        cause = exception,
      )
    }

    degradedCode(vcsResult)?.let { stableCode ->
      throw ReqwsProjectionApplyException(stableCode = stableCode, degraded = true)
    }
    digestRecorder.markApplied(snapshot.digestSha256)
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

  private fun degradedCode(result: VcsMappingApplyResult): String? {
    val codes = result.plan.diagnostics.mapTo(mutableSetOf()) { it.code }
    return when {
      VcsMappingDiagnosticCode.OWNERSHIP_CONFLICT in codes ||
        VcsMappingDiagnosticCode.DUPLICATE_MAPPING in codes ->
        ReqwsStableErrorCode.OWNERSHIP_CONFLICT
      VcsMappingDiagnosticCode.REPOSITORY_NOT_GIT in codes ->
        ReqwsStableErrorCode.VCS_MAPPING_APPLY_FAILED
      VcsMappingDiagnosticCode.REPOSITORY_MISSING in codes ->
        ManifestErrorCode.REPOSITORY_MISSING.name
      else -> null
    }
  }

  private fun safeModeFailure() = ReqwsProjectionApplyException(
    stableCode = ReqwsStableErrorCode.SAFE_MODE_BLOCKED,
    degraded = false,
  )

  companion object {
    fun forProject(project: Project): ReqwsProjectionApplier {
      val persistence = project.service<ReqwsSyncPersistence>()
      return ReqwsProjectionApplier(
        isTrusted = { TrustedProjects.isProjectTrusted(project) },
        projectModel = ProjectModelProjection { snapshot ->
          project.service<ReqwsProjectModelAdapter>().apply(snapshot)
        },
        vcsMappings = VcsMappingProjection { snapshot ->
          project.service<ReqwsVcsMappingService>().apply(snapshot)
        },
        digestRecorder = AppliedDigestRecorder(persistence::markApplied),
      )
    }
  }
}
