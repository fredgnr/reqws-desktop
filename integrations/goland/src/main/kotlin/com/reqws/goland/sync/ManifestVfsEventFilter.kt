package com.reqws.goland.sync

import java.nio.file.Path

internal enum class ManifestVfsEventKind {
  CREATE,
  DELETE,
  MOVE,
  RENAME,
  REPLACE,
  CONTENT_CHANGE,
  OTHER,
}

/**
 * Platform-neutral representation of the before/after paths carried by a VFS event.
 * A future IntelliJ listener adapter is responsible only for translating platform events here.
 */
internal data class ManifestVfsEvent(
  val kind: ManifestVfsEventKind,
  val beforePath: Path? = null,
  val afterPath: Path? = null,
) {
  init {
    require(beforePath != null || afterPath != null) {
      "A VFS event must contain a before or after path"
    }
  }
}

/** Matches only the exact manifest or the manifest's direct parent directory. */
internal class ManifestVfsEventFilter(manifestPath: Path) {
  private val exactManifestPath = normalizeAbsolute(manifestPath)
  private val directParentPath = requireNotNull(exactManifestPath.parent) {
    "The manifest path must have a direct parent"
  }

  fun accepts(event: ManifestVfsEvent): Boolean {
    if (event.kind == ManifestVfsEventKind.OTHER) return false
    return sequenceOf(event.beforePath, event.afterPath)
      .filterNotNull()
      .map(::normalizeAbsolute)
      .any { path -> path == exactManifestPath || path == directParentPath }
  }

  private fun normalizeAbsolute(path: Path): Path {
    require(path.isAbsolute) { "VFS paths must be absolute: $path" }
    return path.normalize()
  }
}
