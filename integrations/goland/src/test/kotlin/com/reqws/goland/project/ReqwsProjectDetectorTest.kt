package com.reqws.goland.project

import com.reqws.goland.manifest.ManifestReader
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
  fun `detects only the fixed manifest directory entry`() {
    val root = temporaryFolder.newFolder("workspace").toPath()
    assertNull(ReqwsProjectDetector.detect(root))

    val manifest = root.resolve(ManifestReader.MANIFEST_RELATIVE_PATH)
    Files.createDirectories(manifest.parent)
    Files.writeString(manifest, "{}")

    assertEquals(manifest, ReqwsProjectDetector.detect(root))
  }

  @Test
  fun `detects a symlink so the reader can report it as invalid`() {
    val root = temporaryFolder.newFolder("symlink-workspace").toPath()
    val target = temporaryFolder.newFile("outside.json").toPath()
    val manifest = root.resolve(ManifestReader.MANIFEST_RELATIVE_PATH)
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
