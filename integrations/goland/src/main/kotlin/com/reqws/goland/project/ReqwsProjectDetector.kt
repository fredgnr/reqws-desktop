package com.reqws.goland.project

import com.intellij.openapi.project.Project
import com.reqws.goland.manifest.ManifestReader
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path

internal object ReqwsProjectDetector {
  fun projectRoot(project: Project): Path? = project.basePath
    ?.let(::pathOrNull)
    ?.toAbsolutePath()
    ?.normalize()

  fun manifestPath(projectRoot: Path): Path =
    projectRoot.toAbsolutePath().normalize().resolve(ManifestReader.MANIFEST_RELATIVE_PATH)

  fun canonicalProjectRoot(projectRoot: Path): Path? = try {
    projectRoot.toRealPath().takeIf(Files::isDirectory)
  } catch (_: Exception) {
    null
  }

  fun detect(project: Project): Path? = projectRoot(project)?.let(::detect)

  /**
   * Detects the fixed directory entry without following it. A symlink still activates
   * ReqWS so [ManifestReader] can reject it with a stable diagnostic instead of hiding it.
   */
  fun detect(projectRoot: Path): Path? {
    val candidate = manifestPath(projectRoot)
    return candidate.takeIf { Files.exists(it, LinkOption.NOFOLLOW_LINKS) }
  }

  private fun pathOrNull(value: String): Path? = try {
    Path.of(value)
  } catch (_: Exception) {
    null
  }
}
