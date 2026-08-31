package com.reqws.goland.diagnostics

import com.reqws.goland.manifest.ManifestReader
import com.reqws.goland.project.ReqwsProjectState
import com.reqws.goland.projectmodel.REQWS_MODEL_STRATEGY
import com.reqws.goland.vcs.VcsRepositoryStatus
import java.nio.file.Path

internal object ReqwsDiagnostics {
  const val STRATEGY = REQWS_MODEL_STRATEGY

  fun format(
    pluginVersion: String,
    ideBuild: String,
    projectRoot: Path?,
    state: ReqwsProjectState,
    userHome: Path? = systemUserHome(),
  ): String {
    val snapshot = state.snapshot
    val diagnosticRoot = snapshot?.canonicalProjectRoot ?: projectRoot
    val manifestPath = snapshot?.manifestPath
      ?: projectRoot?.toAbsolutePath()?.normalize()?.resolve(ManifestReader.MANIFEST_RELATIVE_PATH)
    val vcsInspection = state.vcsInspection
    val configuredGitRootCount = vcsInspection?.repositoryStatuses.orEmpty().count {
      it.status == VcsRepositoryStatus.CONFIGURED
    }
    val manualGitRootCount = vcsInspection?.repositoryStatuses.orEmpty().count {
      it.status == VcsRepositoryStatus.NOT_CONFIGURED ||
        it.status == VcsRepositoryStatus.WRONG_VCS ||
        it.status == VcsRepositoryStatus.DUPLICATE
    }
    val vcsRepositoryStatuses = vcsInspection?.repositoryStatuses.orEmpty()
      .sortedBy { it.repositoryIndex }
      .joinToString(",") { "${it.repositoryIndex}:${it.status.name}" }
    val vcsWorkspaceDiagnostics = vcsInspection?.workspaceDiagnostics.orEmpty()
      .map { it.name }
      .sorted()
      .joinToString(",")
    return buildList {
      add("pluginVersion=$pluginVersion")
      add("ideBuild=$ideBuild")
      add("strategy=$STRATEGY")
      add("lifecycle=${state.lifecycle.name}")
      add("projectRoot=${redactPath(diagnosticRoot, userHome)}")
      add("manifestPath=${redactPath(manifestPath, userHome)}")
      add("lastAppliedDigest=${state.lastAppliedDigest.orEmpty()}")
      add("candidateDigest=${state.lastError?.digestSha256 ?: snapshot?.digestSha256.orEmpty()}")
      add("repositoryCount=${snapshot?.repositories?.size ?: 0}")
      add("missingRepositoryCount=${snapshot?.missingRepositoryCount ?: 0}")
      add("vcsMode=READ_ONLY_MANUAL")
      add("configuredGitRootCount=$configuredGitRootCount")
      add("manualGitRootCount=$manualGitRootCount")
      add("vcsDiagnosticCode=${vcsInspection?.stableErrorCode().orEmpty()}")
      add("vcsRepositoryStatuses=$vcsRepositoryStatuses")
      add("vcsWorkspaceDiagnostics=$vcsWorkspaceDiagnostics")
      add("errorCode=${state.lastError?.code.orEmpty()}")
      add("errorField=${state.lastError?.field.orEmpty()}")
    }.joinToString(separator = "\n")
  }

  internal fun redactPath(path: Path?, userHome: Path?): String {
    if (path == null) return ""
    val normalized = path.toAbsolutePath().normalize()
    val normalizedHome = userHome?.toAbsolutePath()?.normalize()
    if (normalizedHome != null && normalized.startsWith(normalizedHome)) {
      val relative = normalizedHome.relativize(normalized)
      return if (relative.nameCount == 0) "~" else "~/$relative"
    }
    return normalized.fileName?.let { "<absolute>/$it" } ?: "<absolute>"
  }

  private fun systemUserHome(): Path? = try {
    System.getProperty("user.home")?.let(Path::of)
  } catch (_: Exception) {
    null
  }
}
