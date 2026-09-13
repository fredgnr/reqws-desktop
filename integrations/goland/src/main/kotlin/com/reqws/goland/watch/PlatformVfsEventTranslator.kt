package com.reqws.goland.watch

import com.intellij.openapi.vfs.newvfs.events.VFileContentChangeEvent
import com.intellij.openapi.vfs.newvfs.events.VFileCreateEvent
import com.intellij.openapi.vfs.newvfs.events.VFileDeleteEvent
import com.intellij.openapi.vfs.newvfs.events.VFileEvent
import com.intellij.openapi.vfs.newvfs.events.VFileMoveEvent
import com.intellij.openapi.vfs.newvfs.events.VFilePropertyChangeEvent
import com.reqws.goland.sync.ManifestVfsEvent
import com.reqws.goland.sync.ManifestVfsEventKind
import java.nio.file.InvalidPathException
import java.nio.file.Path

/** Converts IntelliJ VFS notifications into the platform-neutral paths used by the filter. */
internal object PlatformVfsEventTranslator {
  fun translate(event: VFileEvent): ManifestVfsEvent? = when (event) {
    is VFileContentChangeEvent -> translatePaths(
      kind = ManifestVfsEventKind.CONTENT_CHANGE,
      afterPath = event.path,
    )

    is VFileCreateEvent -> translatePaths(
      kind = ManifestVfsEventKind.CREATE,
      afterPath = event.path,
    )

    is VFileDeleteEvent -> translatePaths(
      kind = ManifestVfsEventKind.DELETE,
      beforePath = event.path,
    )

    is VFileMoveEvent -> translatePaths(
      kind = ManifestVfsEventKind.MOVE,
      beforePath = event.oldPath,
      afterPath = event.newPath,
    )

    is VFilePropertyChangeEvent -> if (event.isRename) {
      translatePaths(
        kind = ManifestVfsEventKind.RENAME,
        beforePath = event.oldPath,
        afterPath = event.newPath,
      )
    } else {
      null
    }

    else -> null
  }

  internal fun translatePaths(
    kind: ManifestVfsEventKind,
    beforePath: String? = null,
    afterPath: String? = null,
  ): ManifestVfsEvent? {
    val normalizedBeforePath = beforePath?.let(::pathOrNull)
    val normalizedAfterPath = afterPath?.let(::pathOrNull)
    if (normalizedBeforePath == null && normalizedAfterPath == null) return null
    return ManifestVfsEvent(kind, normalizedBeforePath, normalizedAfterPath)
  }

  private fun pathOrNull(rawPath: String): Path? = try {
    Path.of(rawPath)
      .takeIf(Path::isAbsolute)
      ?.normalize()
  } catch (_: InvalidPathException) {
    null
  }
}
