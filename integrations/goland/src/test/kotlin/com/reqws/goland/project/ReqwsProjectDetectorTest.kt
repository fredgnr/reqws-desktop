package com.reqws.goland.project

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Files

class ReqwsProjectDetectorTest {
  @Rule
  @JvmField
  val temporaryFolder = TemporaryFolder()

  @Test
  fun `detects only the fixed shell binding and leaves the workspace root unmanaged`() {
    val workspace = temporaryFolder.newFolder("workspace").toPath()
    val root = workspace.resolve(".reqws/ide/goland")
    assertNull(ReqwsProjectDetector.detect(root))

    val manifest = root.resolve("reqws-project.json")
    Files.createDirectories(manifest.parent)
    Files.writeString(manifest, "{}")

    assertEquals(manifest, ReqwsProjectDetector.detect(root))
    assertNull(ReqwsProjectDetector.detect(root.parent.parent.parent))
  }

  @Test
  fun `detects a symlink so the reader can report it as invalid`() {
    val root = temporaryFolder.newFolder("symlink-workspace").toPath().resolve(".reqws/ide/goland")
    val target = temporaryFolder.newFile("outside.json").toPath()
    val manifest = root.resolve("reqws-project.json")
    Files.createDirectories(manifest.parent)
    Files.createSymbolicLink(manifest, target)

    assertEquals(manifest, ReqwsProjectDetector.detect(root))
  }

  @Test
  fun `canonicalizes a symlinked project root for VFS watcher identity`() {
    val root = temporaryFolder.newFolder("canonical-watcher-root").toPath()
    val alias = temporaryFolder.root.toPath().resolve("watcher-root-alias")
    Files.createSymbolicLink(alias, root)

    assertEquals(root.toRealPath(), ReqwsProjectDetector.canonicalProjectRoot(alias))
  }
}
