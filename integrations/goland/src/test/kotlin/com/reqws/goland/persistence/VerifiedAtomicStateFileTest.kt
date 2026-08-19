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
import java.util.concurrent.atomic.AtomicBoolean

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
  fun `parent replacement during write cannot redirect state operations`() {
    val parent = temporaryFolder.newFolder("parent-swap").toPath()
    val detached = parent.resolveSibling("parent-swap-detached")
    val outside = temporaryFolder.newFolder("parent-swap-outside").toPath()
    val state = parent.resolve("state")
    val outsideState = outside.resolve("state")
    val outsideSentinel = outside.resolve("sentinel")
    Files.writeString(state, "previous")
    Files.writeString(outsideState, "outside")
    Files.writeString(outsideSentinel, "untouched")

    val failure = assertThrows(VerifiedAtomicStateFileException::class.java) {
      stateFile(
        state,
        ParentSwapOperations(parent, detached, outside),
      ).writeAndVerify("replacement")
    }

    assertTrue(failure.message.orEmpty().contains("identity"))
    assertTrue(Files.isSymbolicLink(parent))
    assertEquals("outside", Files.readString(outsideState))
    assertEquals("untouched", Files.readString(outsideSentinel))
    assertEquals("replacement", Files.readString(detached.resolve("state")))
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

    override fun openStableDirectory(path: Path): AtomicDirectoryOperations =
      RecordingDirectory(NioAtomicFileOperations.openStableDirectory(path))

    private fun attempt(stage: Stage) {
      attempted.add(stage)
      if (failAt == stage) throw IOException("Injected $stage failure")
      completed.add(stage)
    }

    private inner class RecordingDirectory(
      private val delegate: AtomicDirectoryOperations,
    ) : AtomicDirectoryOperations {
      override fun verifyCurrent() = delegate.verifyCurrent()

      override fun requireAbsentOrRegularFile(name: Path) =
        delegate.requireAbsentOrRegularFile(name)

      override fun requireAtomicReplaceSupported() = delegate.requireAtomicReplaceSupported()

      override fun readRegularFile(name: Path, maxBytes: Int): ByteArray? {
        if (replaced) {
          attempt(Stage.READBACK)
          readbackOverride?.let { return it.toByteArray(StandardCharsets.UTF_8) }
        }
        return delegate.readRegularFile(name, maxBytes)
      }

      override fun createPrivateTempFile(prefix: String, suffix: String): AtomicTemporaryFile =
        RecordingTemporaryFile(delegate.createPrivateTempFile(prefix, suffix))

      override fun atomicReplace(source: Path, target: Path) {
        attempt(Stage.MOVE)
        delegate.atomicReplace(source, target)
        replaced = true
      }

      override fun forceDirectory() {
        attempt(Stage.DIRECTORY_FORCE)
        delegate.forceDirectory()
      }

      override fun openLockFile(name: Path) = delegate.openLockFile(name)

      override fun deleteIfExists(name: Path) = delegate.deleteIfExists(name)

      override fun close() = delegate.close()
    }

    private inner class RecordingTemporaryFile(
      private val delegate: AtomicTemporaryFile,
    ) : AtomicTemporaryFile {
      override val fileName: Path = delegate.fileName

      override fun write(bytes: ByteArray) {
        attempt(Stage.WRITE)
        delegate.write(bytes)
      }

      override fun force() {
        attempt(Stage.FILE_FORCE)
        delegate.force()
      }

      override fun close() = delegate.close()
    }
  }

  private class ParentSwapOperations(
    private val parent: Path,
    private val detached: Path,
    private val outside: Path,
  ) : AtomicFileOperations {
    private val swapped = AtomicBoolean()

    override fun openStableDirectory(path: Path): AtomicDirectoryOperations =
      ParentSwapDirectory(NioAtomicFileOperations.openStableDirectory(path))

    private inner class ParentSwapDirectory(
      private val delegate: AtomicDirectoryOperations,
    ) : AtomicDirectoryOperations by delegate {
      override fun createPrivateTempFile(prefix: String, suffix: String): AtomicTemporaryFile {
        if (swapped.compareAndSet(false, true)) {
          Files.move(parent, detached)
          Files.createSymbolicLink(parent, outside)
        }
        return delegate.createPrivateTempFile(prefix, suffix)
      }
    }
  }
}
