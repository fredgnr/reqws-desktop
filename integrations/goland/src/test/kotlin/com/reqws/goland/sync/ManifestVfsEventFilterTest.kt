package com.reqws.goland.sync

import java.nio.file.Path
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ManifestVfsEventFilterTest {
  private val root = Path.of("/tmp/reqws-filter-workspace")
  private val manifest = root.resolve(".reqws/workspace.json")
  private val parent = manifest.parent
  private val filter = ManifestVfsEventFilter(manifest)

  @Test
  fun `accepts all supported changes to the exact manifest`() {
    ManifestVfsEventKind.entries
      .filterNot { it == ManifestVfsEventKind.OTHER }
      .forEach { kind ->
        assertTrue(kind.name, filter.accepts(ManifestVfsEvent(kind, afterPath = manifest)))
      }
  }

  @Test
  fun `accepts a move or rename whose before or after path is the manifest`() {
    assertTrue(
      filter.accepts(
        ManifestVfsEvent(
          kind = ManifestVfsEventKind.MOVE,
          beforePath = parent.resolve("workspace.json.tmp"),
          afterPath = manifest,
        ),
      ),
    )
    assertTrue(
      filter.accepts(
        ManifestVfsEvent(
          kind = ManifestVfsEventKind.RENAME,
          beforePath = manifest,
          afterPath = parent.resolve("workspace.json.old"),
        ),
      ),
    )
  }

  @Test
  fun `accepts an event on the direct parent directory`() {
    assertTrue(
      filter.accepts(
        ManifestVfsEvent(ManifestVfsEventKind.DELETE, beforePath = parent),
      ),
    )
    assertTrue(
      filter.accepts(
        ManifestVfsEvent(
          ManifestVfsEventKind.CREATE,
          afterPath = root.resolve("other/../.reqws"),
        ),
      ),
    )
  }

  @Test
  fun `rejects siblings descendants prefixes and unrelated event kinds`() {
    listOf(
      parent.resolve("workspace.json.tmp"),
      parent.resolve("workspace.json.backup"),
      manifest.resolve("nested"),
      root.resolve(".reqws-other/workspace.json"),
      root,
    ).forEach { path ->
      assertFalse(
        path.toString(),
        filter.accepts(ManifestVfsEvent(ManifestVfsEventKind.CONTENT_CHANGE, afterPath = path)),
      )
    }
    assertFalse(
      filter.accepts(ManifestVfsEvent(ManifestVfsEventKind.OTHER, afterPath = manifest)),
    )
  }

  @Test(expected = IllegalArgumentException::class)
  fun `rejects relative event paths`() {
    filter.accepts(
      ManifestVfsEvent(ManifestVfsEventKind.CREATE, afterPath = Path.of(".reqws/workspace.json")),
    )
  }
}
