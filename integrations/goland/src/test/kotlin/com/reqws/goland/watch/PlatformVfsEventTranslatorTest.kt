package com.reqws.goland.watch

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.openapi.vfs.newvfs.BulkFileListener
import com.intellij.openapi.vfs.newvfs.events.VFileContentChangeEvent
import com.intellij.openapi.vfs.newvfs.events.VFileCreateEvent
import com.intellij.openapi.vfs.newvfs.events.VFileDeleteEvent
import com.intellij.openapi.vfs.newvfs.events.VFileEvent
import com.intellij.openapi.vfs.newvfs.events.VFileMoveEvent
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.reqws.goland.sync.ManifestVfsEvent
import com.reqws.goland.sync.ManifestVfsEventKind
import java.nio.charset.StandardCharsets
import java.nio.file.Path

class PlatformVfsEventTranslatorTest : BasePlatformTestCase() {
  fun testTranslatesContentCreateAndDeletePathsWithoutReadingFiles() {
    val parent = myFixture.tempDirFixture.findOrCreateDir("translate-basic/.reqws")
    val manifest = myFixture.tempDirFixture.createFile("translate-basic/.reqws/workspace.json", "{}")
    val manifestPath = path(manifest)

    val contentEvents = captureAfterEvents {
      manifest.setBinaryContent("{\"updated\":true}".toByteArray(StandardCharsets.UTF_8))
    }
    assertTrue(contentEvents.any { it is VFileContentChangeEvent })
    val content = findTranslatedEvent(
      contentEvents,
      ManifestVfsEventKind.CONTENT_CHANGE,
      afterPath = manifestPath,
    )
    assertNull(content.beforePath)

    val createdPath = path(parent).resolve("replacement.json")
    val createEvents = captureAfterEvents {
      parent.createChildData(this, "replacement.json")
    }
    assertTrue(createEvents.any { it is VFileCreateEvent })
    val create = findTranslatedEvent(
      createEvents,
      ManifestVfsEventKind.CREATE,
      afterPath = createdPath,
    )
    assertNull(create.beforePath)

    val deleteEvents = captureAfterEvents {
      manifest.delete(this)
    }
    assertTrue(deleteEvents.any { it is VFileDeleteEvent })
    val delete = findTranslatedEvent(
      deleteEvents,
      ManifestVfsEventKind.DELETE,
      beforePath = manifestPath,
    )
    assertNull(delete.afterPath)
  }

  fun testMoveAndRenameRetainBothBeforeAndAfterPaths() {
    val source = myFixture.tempDirFixture.createFile("translate-paths/from/workspace.json", "{}")
    val sourcePath = path(source)
    val targetParent = myFixture.tempDirFixture.findOrCreateDir("translate-paths/to")
    val movedPath = path(targetParent).resolve("workspace.json")

    val moveEvents = captureAfterEvents {
      source.move(this, targetParent)
    }
    assertTrue(moveEvents.any { it is VFileMoveEvent })
    findTranslatedEvent(
      moveEvents,
      ManifestVfsEventKind.MOVE,
      beforePath = sourcePath,
      afterPath = movedPath,
    )

    val renamedPath = path(targetParent).resolve("workspace-renamed.json")
    val rename = requireNotNull(
      PlatformVfsEventTranslator.translatePaths(
        kind = ManifestVfsEventKind.RENAME,
        beforePath = movedPath.toString(),
        afterPath = renamedPath.toString(),
      ),
    )
    assertEquals(ManifestVfsEventKind.RENAME, rename.kind)
    assertEquals(movedPath, rename.beforePath)
    assertEquals(renamedPath, rename.afterPath)
  }

  private fun captureAfterEvents(action: () -> Unit): List<VFileEvent> {
    val events = mutableListOf<VFileEvent>()
    val connection = project.messageBus.connect(testRootDisposable)
    connection.subscribe(
      VirtualFileManager.VFS_CHANGES,
      object : BulkFileListener {
        override fun after(batch: List<VFileEvent>) {
          events.addAll(batch)
        }
      },
    )
    ApplicationManager.getApplication().runWriteAction(action)
    connection.disconnect()
    return events
  }

  private fun findTranslatedEvent(
    events: List<VFileEvent>,
    kind: ManifestVfsEventKind,
    beforePath: Path? = null,
    afterPath: Path? = null,
  ): ManifestVfsEvent = events.asSequence()
    .mapNotNull(PlatformVfsEventTranslator::translate)
    .firstOrNull { event ->
      event.kind == kind &&
        (beforePath == null || event.beforePath == beforePath) &&
        (afterPath == null || event.afterPath == afterPath)
    }
    ?: throw AssertionError(
      "No translated $kind event matched before=$beforePath after=$afterPath; " +
        "captured=${events.map { it.javaClass.simpleName }}",
    )

  private fun path(file: VirtualFile): Path = Path.of(file.path).normalize()
}
