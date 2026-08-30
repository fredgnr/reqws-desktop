package com.reqws.goland.projectmodel

import com.reqws.goland.persistence.AtomicDirectoryOperations
import com.reqws.goland.persistence.AtomicFileOperations
import com.reqws.goland.persistence.AtomicTemporaryFile
import com.reqws.goland.persistence.NioAtomicFileOperations
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class ReqwsManagedModelFileStateTest {
  @Rule
  @JvmField
  val temporaryFolder = TemporaryFolder()

  @Test
  fun `persists at the fixed idea path and advances a verified generation`() {
    val root = root("round-trip")
    val binding = managedModelStateBinding(WORKSPACE_ID, root)
    val repository = VerifiedManagedModelStateRepository(root)

    val first = repository.write(binding, null, state(binding, EPOCH_A))
    val second = repository.write(
      binding,
      first.generation,
      first.copy(
        managedClaims = listOf(DurableManagedClaim(".reqws", TOKEN_A)),
        targetModuleName = "workspace",
      ),
    )

    assertEquals(0L, first.generation)
    assertEquals(1L, second.generation)
    assertEquals(second, repository.read(binding))
    assertTrue(Files.isRegularFile(root.resolve(".idea").resolve(REQWS_MODEL_STATE_FILE_NAME)))
    assertFalse(Files.exists(root.resolve(REQWS_MODEL_STATE_FILE_NAME)))
  }

  @Test
  fun `rejects a stale generation without overwriting the current state`() {
    val root = root("generation")
    val binding = managedModelStateBinding(WORKSPACE_ID, root)
    val repository = VerifiedManagedModelStateRepository(root)
    val first = repository.write(binding, null, state(binding, EPOCH_A))

    val failure = assertThrows(ProjectModelApplyException::class.java) {
      repository.write(binding, null, state(binding, EPOCH_B))
    }

    assertEquals(ProjectModelErrorCode.INVALID_OWNERSHIP_STATE, failure.code)
    assertEquals(first, repository.read(binding))
  }

  @Test
  fun `shape validation rejects dot and traversal claims before state replacement`() {
    listOf(".", "..").forEachIndexed { index, relative ->
      val root = root("unsafe-$index")
      val binding = managedModelStateBinding(WORKSPACE_ID, root)
      val repository = VerifiedManagedModelStateRepository(root)

      val failure = assertThrows(ProjectModelApplyException::class.java) {
        repository.write(
          binding,
          null,
          state(
            binding,
            EPOCH_A,
            targetModuleName = "workspace",
            managedClaims = listOf(DurableManagedClaim(relative, TOKEN_A)),
          ),
        )
      }

      assertEquals(ProjectModelErrorCode.INVALID_OWNERSHIP_STATE, failure.code)
      assertFalse(Files.exists(root.resolve(".idea").resolve(REQWS_MODEL_STATE_FILE_NAME)))
    }
  }

  @Test
  fun `strict read rejects unknown JSON fields and invalid UTF8`() {
    val unknownRoot = root("unknown-json")
    val unknownBinding = managedModelStateBinding(WORKSPACE_ID, unknownRoot)
    val unknownRepository = VerifiedManagedModelStateRepository(unknownRoot)
    unknownRepository.write(unknownBinding, null, state(unknownBinding, EPOCH_A))
    val unknownFile = unknownRoot.resolve(".idea").resolve(REQWS_MODEL_STATE_FILE_NAME)
    val unknownJson = Files.readString(unknownFile).replaceFirst("{", "{\"unknown\":true,")
    Files.writeString(unknownFile, unknownJson)

    assertEquals(
      ProjectModelErrorCode.INVALID_OWNERSHIP_STATE,
      assertThrows(ProjectModelApplyException::class.java) {
        unknownRepository.read(unknownBinding)
      }.code,
    )

    val utf8Root = root("invalid-utf8")
    val utf8Binding = managedModelStateBinding(WORKSPACE_ID, utf8Root)
    val utf8File = utf8Root.resolve(".idea").resolve(REQWS_MODEL_STATE_FILE_NAME)
    Files.write(utf8File, byteArrayOf(0xc3.toByte(), 0x28))

    assertEquals(
      ProjectModelErrorCode.INVALID_OWNERSHIP_STATE,
      assertThrows(ProjectModelApplyException::class.java) {
        VerifiedManagedModelStateRepository(utf8Root).read(utf8Binding)
      }.code,
    )
  }

  @Test
  fun `rejects a state bound to another workspace`() {
    val root = root("binding")
    val binding = managedModelStateBinding(WORKSPACE_ID, root)
    val repository = VerifiedManagedModelStateRepository(root)
    repository.write(binding, null, state(binding, EPOCH_A))

    val failure = assertThrows(ProjectModelApplyException::class.java) {
      repository.read(binding.copy(workspaceId = "another-workspace"))
    }

    assertEquals(ProjectModelErrorCode.INVALID_OWNERSHIP_STATE, failure.code)
  }

  @Test
  fun `rejects a symlinked idea directory`() {
    val root = temporaryFolder.newFolder("symlink-root").toPath().toRealPath()
    val outside = temporaryFolder.newFolder("outside-idea").toPath().toRealPath()
    Files.createSymbolicLink(root.resolve(".idea"), outside)
    val binding = managedModelStateBinding(WORKSPACE_ID, root)

    val failure = assertThrows(ProjectModelApplyException::class.java) {
      VerifiedManagedModelStateRepository(root).read(binding)
    }

    assertEquals(ProjectModelErrorCode.INVALID_OWNERSHIP_STATE, failure.code)
    assertTrue(Files.list(outside).use { children -> children.findAny().isEmpty() })
  }

  @Test
  fun `rejects a non-regular or symlinked lock file before replacing state`() {
    listOf("directory", "symlink").forEach { kind ->
      val root = root("unsafe-lock-$kind")
      val lock = lockFile(root)
      if (kind == "directory") {
        Files.createDirectory(lock)
      } else {
        val outside = temporaryFolder.newFile("outside-lock").toPath()
        Files.createSymbolicLink(lock, outside)
      }
      val binding = managedModelStateBinding(WORKSPACE_ID, root)

      val failure = assertThrows(ProjectModelApplyException::class.java) {
        VerifiedManagedModelStateRepository(root).write(binding, null, state(binding, EPOCH_A))
      }

      assertEquals(ProjectModelErrorCode.INVALID_OWNERSHIP_STATE, failure.code)
      assertFalse(Files.exists(root.resolve(".idea").resolve(REQWS_MODEL_STATE_FILE_NAME)))
    }
  }

  @Test
  fun `rejects an overlapping writer lock before replacing state`() {
    val root = root("overlapping-lock")
    val binding = managedModelStateBinding(WORKSPACE_ID, root)
    val lock = lockFile(root)
    Files.createFile(lock)

    FileChannel.open(lock, StandardOpenOption.WRITE).use { channel ->
      channel.lock().use {
        val failure = assertThrows(ProjectModelApplyException::class.java) {
          VerifiedManagedModelStateRepository(root).write(binding, null, state(binding, EPOCH_A))
        }
        assertEquals(ProjectModelErrorCode.INVALID_OWNERSHIP_STATE, failure.code)
      }
    }
    assertFalse(Files.exists(root.resolve(".idea").resolve(REQWS_MODEL_STATE_FILE_NAME)))
  }

  @Test
  fun `replacing the child lock cannot bypass the stable directory writer lock`() {
    val root = root("replaced-child-lock")
    val binding = managedModelStateBinding(WORKSPACE_ID, root)
    val seeded = VerifiedManagedModelStateRepository(root).write(
      binding,
      null,
      state(binding, EPOCH_A),
    )
    val firstWriterHoldingLocks = CountDownLatch(1)
    val releaseFirstWriter = CountDownLatch(1)
    val firstRepository = VerifiedManagedModelStateRepository(
      root,
      HeldWriterOperations(firstWriterHoldingLocks, releaseFirstWriter),
    )
    val executor = Executors.newSingleThreadExecutor()

    try {
      val firstWrite = executor.submit<DurableManagedModelState> {
        firstRepository.write(
          binding,
          seeded.generation,
          seeded.copy(writerJvmEpoch = EPOCH_B),
        )
      }
      assertTrue(firstWriterHoldingLocks.await(10, TimeUnit.SECONDS))

      val lock = lockFile(root)
      val detachedLock = lock.resolveSibling("${lock.fileName}.detached")
      Files.move(lock, detachedLock)
      Files.createFile(lock)
      FileChannel.open(lock, StandardOpenOption.WRITE).use { replacementChannel ->
        replacementChannel.tryLock().use { replacementLock ->
          assertTrue("The replacement child inode must be independently lockable", replacementLock != null)
        }
      }

      val failure = assertThrows(ProjectModelApplyException::class.java) {
        VerifiedManagedModelStateRepository(root).write(
          binding,
          seeded.generation,
          seeded.copy(writerJvmEpoch = EPOCH_A),
        )
      }
      assertEquals(ProjectModelErrorCode.INVALID_OWNERSHIP_STATE, failure.code)
      assertEquals(seeded, VerifiedManagedModelStateRepository(root).read(binding))

      releaseFirstWriter.countDown()
      val persisted = firstWrite.get(10, TimeUnit.SECONDS)
      assertEquals(seeded.generation + 1L, persisted.generation)
      assertEquals(EPOCH_B, persisted.writerJvmEpoch)
      assertEquals(persisted, VerifiedManagedModelStateRepository(root).read(binding))

      val staleFailure = assertThrows(ProjectModelApplyException::class.java) {
        VerifiedManagedModelStateRepository(root).write(
          binding,
          seeded.generation,
          seeded.copy(writerJvmEpoch = EPOCH_A),
        )
      }
      assertEquals(ProjectModelErrorCode.INVALID_OWNERSHIP_STATE, staleFailure.code)
      assertEquals(persisted, VerifiedManagedModelStateRepository(root).read(binding))
    } finally {
      releaseFirstWriter.countDown()
      executor.shutdownNow()
    }
  }

  @Test
  fun `parent replacement while locked cannot redirect managed state persistence`() {
    val root = root("parent-swap")
    val idea = root.resolve(".idea")
    val detached = root.resolve(".idea-detached")
    val outside = temporaryFolder.newFolder("parent-swap-outside").toPath()
    val outsideState = outside.resolve(REQWS_MODEL_STATE_FILE_NAME)
    val outsideLock = outside.resolve(".$REQWS_MODEL_STATE_FILE_NAME.lock")
    val binding = managedModelStateBinding(WORKSPACE_ID, root)
    val seeded = VerifiedManagedModelStateRepository(root).write(
      binding,
      null,
      state(binding, EPOCH_A),
    )
    Files.writeString(outsideState, "outside-state")
    val repository = VerifiedManagedModelStateRepository(
      root,
      ParentSwapOperations(idea, detached, outside),
    )

    val failure = assertThrows(ProjectModelApplyException::class.java) {
      repository.write(
        binding,
        seeded.generation,
        seeded.copy(writerJvmEpoch = EPOCH_B),
      )
    }

    assertEquals(ProjectModelErrorCode.INVALID_OWNERSHIP_STATE, failure.code)
    assertTrue(Files.isSymbolicLink(idea))
    assertEquals("outside-state", Files.readString(outsideState))
    assertFalse(Files.exists(outsideLock))
    val detachedState = Files.readString(detached.resolve(REQWS_MODEL_STATE_FILE_NAME))
    assertTrue(detachedState.contains("\"generation\":1"))
    assertTrue(detachedState.contains(EPOCH_B))
    assertTrue(Files.isRegularFile(detached.resolve(".$REQWS_MODEL_STATE_FILE_NAME.lock")))
  }

  private fun root(name: String): Path {
    val root = temporaryFolder.newFolder(name).toPath().toRealPath()
    Files.createDirectory(root.resolve(".idea"))
    return root
  }

  private fun lockFile(root: Path): Path =
    root.resolve(".idea").resolve(".$REQWS_MODEL_STATE_FILE_NAME.lock")

  private fun state(
    binding: ManagedModelStateBinding,
    writerJvmEpoch: String,
    targetModuleName: String = "",
    managedClaims: List<DurableManagedClaim> = emptyList(),
  ) = DurableManagedModelState(
    workspaceId = binding.workspaceId,
    rootFingerprint = binding.rootFingerprint,
    generation = 0L,
    writerJvmEpoch = writerJvmEpoch,
    targetModuleName = targetModuleName,
    managedClaims = managedClaims,
    recoveryClaims = emptyList(),
  )

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
      override fun openLockFile(name: Path): FileChannel {
        if (swapped.compareAndSet(false, true)) {
          Files.move(parent, detached)
          Files.createSymbolicLink(parent, outside)
        }
        return delegate.openLockFile(name)
      }
    }
  }

  private class HeldWriterOperations(
    private val firstWriterHoldingLocks: CountDownLatch,
    private val releaseFirstWriter: CountDownLatch,
  ) : AtomicFileOperations {
    private val blocked = AtomicBoolean()

    override fun openStableDirectory(path: Path): AtomicDirectoryOperations =
      HeldWriterDirectory(NioAtomicFileOperations.openStableDirectory(path))

    private inner class HeldWriterDirectory(
      private val delegate: AtomicDirectoryOperations,
    ) : AtomicDirectoryOperations by delegate {
      override fun createPrivateTempFile(prefix: String, suffix: String): AtomicTemporaryFile {
        if (blocked.compareAndSet(false, true)) {
          firstWriterHoldingLocks.countDown()
          if (!releaseFirstWriter.await(10, TimeUnit.SECONDS)) {
            throw IllegalStateException("Timed out waiting to release the first writer")
          }
        }
        return delegate.createPrivateTempFile(prefix, suffix)
      }
    }
  }

  companion object {
    private const val WORKSPACE_ID = "ws_test"
    private const val TOKEN_A = "11111111111111111111111111111111"
    private const val EPOCH_A =
      "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
    private const val EPOCH_B =
      "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
  }
}
