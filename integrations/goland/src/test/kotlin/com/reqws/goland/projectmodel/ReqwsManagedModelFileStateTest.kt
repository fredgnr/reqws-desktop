package com.reqws.goland.projectmodel

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

  companion object {
    private const val WORKSPACE_ID = "ws_test"
    private const val TOKEN_A = "11111111111111111111111111111111"
    private const val EPOCH_A =
      "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
    private const val EPOCH_B =
      "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
  }
}
