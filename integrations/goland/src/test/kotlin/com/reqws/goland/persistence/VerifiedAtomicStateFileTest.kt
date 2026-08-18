package com.reqws.goland.persistence

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions

class VerifiedAtomicStateFileTest {
  @Rule
  @JvmField
  val temporaryFolder = TemporaryFolder()

  @Test
  fun `writes forces atomically replaces forces directory and strictly reads back`() {
    val fixture = fixture()
    val operations = RecordingOperations()
    val stateFile = stateFile(fixture, operations)

    stateFile.writeAndVerify("durable-intent")

    assertEquals(
      listOf(Stage.WRITE, Stage.FILE_FORCE, Stage.MOVE, Stage.DIRECTORY_FORCE, Stage.READBACK),
      operations.completed,
    )
    assertEquals("durable-intent", stateFile.read())
    assertEquals(
      PosixFilePermissions.fromString("rw-------"),
      Files.getPosixFilePermissions(fixture),
    )
  }

  @Test
  fun `fails before replace when the complete write fails`() {
    assertPreReplaceFailure(Stage.WRITE)
  }

  @Test
  fun `fails before replace when the file force fails`() {
    assertPreReplaceFailure(Stage.FILE_FORCE)
  }

  @Test
  fun `fails before replace when the atomic move fails`() {
    assertPreReplaceFailure(Stage.MOVE)
  }

  @Test
  fun `reports directory force failure after replace`() {
    assertPostReplaceFailure(Stage.DIRECTORY_FORCE)
  }

  @Test
  fun `reports readback failure after replace`() {
    assertPostReplaceFailure(Stage.READBACK)
  }

  @Test
  fun `rejects strict readback mismatch`() {
    val fixture = fixture()
    val operations = RecordingOperations(readbackOverride = "different")

    assertThrows(VerifiedAtomicStateFileException::class.java) {
      stateFile(fixture, operations).writeAndVerify("durable-intent")
    }

    assertEquals("durable-intent", Files.readString(fixture))
    assertTrue(Stage.READBACK in operations.completed)
  }

  @Test
  fun `rejects a symlink target without following it`() {
    val directory = temporaryFolder.newFolder("symlink-target").toPath()
    val outside = temporaryFolder.newFile("outside-state").toPath()
    Files.writeString(outside, "outside")
    val target = directory.resolve("state")
    Files.createSymbolicLink(target, outside)

    assertThrows(VerifiedAtomicStateFileException::class.java) {
      stateFile(target).read()
    }
    assertThrows(VerifiedAtomicStateFileException::class.java) {
      stateFile(target).writeAndVerify("replacement")
    }

    assertEquals("outside", Files.readString(outside))
  }

  @Test
  fun `rejects a symlinked parent without reading or replacing the external state`() {
    val outsideDirectory = temporaryFolder.newFolder("outside-parent").toPath()
    val outsideState = outsideDirectory.resolve("state")
    Files.writeString(outsideState, "outside")
    val linkedParent = temporaryFolder.root.toPath().resolve("linked-parent")
    Files.createSymbolicLink(linkedParent, outsideDirectory)
    val linkedState = linkedParent.resolve("state")

    assertThrows(VerifiedAtomicStateFileException::class.java) {
      stateFile(linkedState).read()
    }
    assertThrows(VerifiedAtomicStateFileException::class.java) {
      stateFile(linkedState).writeAndVerify("replacement")
    }

    assertEquals("outside", Files.readString(outsideState))
  }

  @Test
  fun `rejects an oversized encoded value before creating a temporary file`() {
    val fixture = fixture()
    val operations = RecordingOperations()

    assertThrows(VerifiedAtomicStateFileException::class.java) {
      stateFile(fixture, operations, maxBytes = 4).writeAndVerify("oversized")
    }

    assertTrue(operations.completed.isEmpty())
    assertTrue(Files.notExists(fixture))
  }

  private fun assertPreReplaceFailure(stage: Stage) {
    val fixture = fixture()
    Files.writeString(fixture, "previous")
    val operations = RecordingOperations(failAt = stage)

    assertThrows(VerifiedAtomicStateFileException::class.java) {
      stateFile(fixture, operations).writeAndVerify("durable-intent")
    }

    assertEquals("previous", Files.readString(fixture))
    assertTrue(stage in operations.attempted)
    assertTrue(Stage.MOVE !in operations.completed)
  }

  private fun assertPostReplaceFailure(stage: Stage) {
    val fixture = fixture()
    Files.writeString(fixture, "previous")
    val operations = RecordingOperations(failAt = stage)

    assertThrows(VerifiedAtomicStateFileException::class.java) {
      stateFile(fixture, operations).writeAndVerify("durable-intent")
    }

    assertEquals("durable-intent", Files.readString(fixture))
    assertTrue(Stage.MOVE in operations.completed)
    assertTrue(stage in operations.attempted)
  }

  private fun fixture(): Path = temporaryFolder.newFolder().toPath().resolve("state")

  private fun stateFile(
    path: Path,
    operations: AtomicFileOperations = NioAtomicFileOperations,
    maxBytes: Int = 1024,
  ) = VerifiedAtomicStateFile(
    file = path,
    maxBytes = maxBytes,
    codec = StringCodec,
    operations = operations,
  )

  private object StringCodec : AtomicStateCodec<String> {
    override fun encode(value: String): ByteArray = value.toByteArray(StandardCharsets.UTF_8)

    override fun decode(bytes: ByteArray): String = String(bytes, StandardCharsets.UTF_8)
  }

  private enum class Stage {
    WRITE,
    FILE_FORCE,
    MOVE,
    DIRECTORY_FORCE,
    READBACK,
  }

  private class RecordingOperations(
    private val failAt: Stage? = null,
    private val readbackOverride: String? = null,
  ) : AtomicFileOperations {
    val attempted = mutableListOf<Stage>()
    val completed = mutableListOf<Stage>()
    private var replaced = false

    override fun requireRealDirectory(path: Path) =
      NioAtomicFileOperations.requireRealDirectory(path)

    override fun requireAbsentOrRegularFile(path: Path) =
      NioAtomicFileOperations.requireAbsentOrRegularFile(path)

    override fun readRegularFile(path: Path, maxBytes: Int): ByteArray? {
      if (replaced) {
        attempt(Stage.READBACK)
        readbackOverride?.let { return it.toByteArray(StandardCharsets.UTF_8) }
      }
      return NioAtomicFileOperations.readRegularFile(path, maxBytes)
    }

    override fun createPrivateTempFile(parent: Path, prefix: String, suffix: String): Path =
      NioAtomicFileOperations.createPrivateTempFile(parent, prefix, suffix)

    override fun write(path: Path, bytes: ByteArray) {
      attempt(Stage.WRITE)
      NioAtomicFileOperations.write(path, bytes)
    }

    override fun forceFile(path: Path) {
      attempt(Stage.FILE_FORCE)
      NioAtomicFileOperations.forceFile(path)
    }

    override fun atomicReplace(source: Path, target: Path) {
      attempt(Stage.MOVE)
      NioAtomicFileOperations.atomicReplace(source, target)
      replaced = true
    }

    override fun forceDirectory(path: Path) {
      attempt(Stage.DIRECTORY_FORCE)
      NioAtomicFileOperations.forceDirectory(path)
    }

    override fun deleteIfExists(path: Path) = NioAtomicFileOperations.deleteIfExists(path)

    private fun attempt(stage: Stage) {
      attempted.add(stage)
      if (failAt == stage) throw IOException("Injected $stage failure")
      completed.add(stage)
    }
  }
}
