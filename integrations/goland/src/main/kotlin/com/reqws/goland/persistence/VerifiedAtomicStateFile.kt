package com.reqws.goland.persistence

import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes
import java.util.UUID

internal interface AtomicStateCodec<T> {
  fun encode(value: T): ByteArray

  fun decode(bytes: ByteArray): T
}

internal class VerifiedAtomicStateFileException(
  message: String,
  cause: Throwable? = null,
) : IllegalStateException(message, cause)

internal class VerifiedAtomicStateFile<T>(
  private val file: Path,
  private val maxBytes: Int,
  private val codec: AtomicStateCodec<T>,
  private val validate: (T) -> Unit = {},
  private val operations: AtomicFileOperations = NioAtomicFileOperations,
) {
  init {
    require(maxBytes > 0) { "maxBytes must be positive." }
    require(file.fileName != null) { "The state file must have a file name." }
  }

  fun read(): T? = withStableParent { it.read() }

  fun writeAndVerify(value: T) {
    withStableParent { it.writeAndVerify(value) }
  }

  internal fun <R> withStableParent(block: (StableAccess) -> R): R {
    val parent = file.parent
      ?: throw VerifiedAtomicStateFileException("The atomic state file has no parent directory.")
    val directory = try {
      operations.openStableDirectory(parent)
    } catch (exception: VerifiedAtomicStateFileException) {
      throw exception
    } catch (exception: Exception) {
      throw VerifiedAtomicStateFileException("The atomic state file parent is unsafe.", exception)
    }
    return try {
      directory.use { stableDirectory ->
        stableDirectory.verifyCurrent()
        val result = block(StableAccess(stableDirectory))
        stableDirectory.verifyCurrent()
        result
      }
    } catch (exception: VerifiedAtomicStateFileException) {
      throw exception
    } catch (exception: Exception) {
      throw VerifiedAtomicStateFileException("Unable to access the atomic state directory.", exception)
    }
  }

  internal inner class StableAccess internal constructor(
    private val directory: AtomicDirectoryOperations,
  ) {
    private val fileName: Path = file.fileName

    fun requireAbsentOrRegularFile(name: Path) {
      try {
        directory.requireAbsentOrRegularFile(name)
      } catch (exception: VerifiedAtomicStateFileException) {
        throw exception
      } catch (exception: Exception) {
        throw VerifiedAtomicStateFileException("The atomic sibling path is unsafe.", exception)
      }
    }

    fun openLockFile(name: Path): FileChannel = try {
      directory.openLockFile(name)
    } catch (exception: VerifiedAtomicStateFileException) {
      throw exception
    } catch (exception: Exception) {
      throw VerifiedAtomicStateFileException("Unable to open the atomic sibling lock.", exception)
    }

    fun tryAcquireExclusiveDirectoryLock(): AutoCloseable? = try {
      directory.tryAcquireExclusiveLock()
    } catch (exception: VerifiedAtomicStateFileException) {
      throw exception
    } catch (exception: Exception) {
      throw VerifiedAtomicStateFileException("Unable to lock the atomic state parent.", exception)
    }

    fun read(): T? {
      val bytes = try {
        directory.readRegularFile(fileName, maxBytes)
      } catch (exception: VerifiedAtomicStateFileException) {
        throw exception
      } catch (exception: Exception) {
        throw VerifiedAtomicStateFileException("Unable to read the atomic state file.", exception)
      } ?: return null
      return decodeAndValidate(bytes)
    }

    fun writeAndVerify(value: T) {
      validateValue(value)
      val bytes = try {
        codec.encode(value)
      } catch (exception: VerifiedAtomicStateFileException) {
        throw exception
      } catch (exception: Exception) {
        throw VerifiedAtomicStateFileException("Unable to encode the atomic state file.", exception)
      }
      if (bytes.isEmpty() || bytes.size > maxBytes) {
        throw VerifiedAtomicStateFileException("The encoded atomic state file has an invalid size.")
      }

      try {
        directory.requireAbsentOrRegularFile(fileName)
        directory.requireAtomicReplaceSupported()
      } catch (exception: VerifiedAtomicStateFileException) {
        throw exception
      } catch (exception: Exception) {
        throw VerifiedAtomicStateFileException("The atomic state file path is unsafe.", exception)
      }

      var temporary: AtomicTemporaryFile? = null
      var temporaryName: Path? = null
      try {
        val created = directory.createPrivateTempFile(".$fileName.", ".tmp")
        temporary = created
        temporaryName = created.fileName
        created.write(bytes)
        created.force()
        created.close()
        temporary = null
        directory.atomicReplace(created.fileName, fileName)
        temporaryName = null
        directory.forceDirectory()
        val reloaded = read()
          ?: throw VerifiedAtomicStateFileException("The atomic state file disappeared after replace.")
        if (reloaded != value) {
          throw VerifiedAtomicStateFileException(
            "The atomic state file failed strict read-back verification.",
          )
        }
        directory.verifyCurrent()
      } catch (exception: VerifiedAtomicStateFileException) {
        throw exception
      } catch (exception: AtomicMoveNotSupportedException) {
        throw VerifiedAtomicStateFileException("Atomic state replacement is not supported.", exception)
      } catch (exception: Exception) {
        throw VerifiedAtomicStateFileException("Unable to persist the atomic state file.", exception)
      } finally {
        try {
          temporary?.close()
        } catch (_: Exception) {
          // Cleanup failure must not hide the original persistence failure.
        }
        temporaryName?.let { name ->
          try {
            directory.deleteIfExists(name)
          } catch (_: Exception) {
            // A private temp file may remain; cleanup failure must not hide the primary failure.
          }
        }
      }
    }
  }

  private fun decodeAndValidate(bytes: ByteArray): T {
    if (bytes.isEmpty() || bytes.size > maxBytes) {
      throw VerifiedAtomicStateFileException("The atomic state file has an invalid size.")
    }
    val decoded = try {
      codec.decode(bytes)
    } catch (exception: VerifiedAtomicStateFileException) {
      throw exception
    } catch (exception: Exception) {
      throw VerifiedAtomicStateFileException("Unable to decode the atomic state file.", exception)
    }
    validateValue(decoded)
    return decoded
  }

  private fun validateValue(value: T) {
    try {
      validate(value)
    } catch (exception: VerifiedAtomicStateFileException) {
      throw exception
    } catch (exception: Exception) {
      throw VerifiedAtomicStateFileException("The atomic state file failed validation.", exception)
    }
  }
}

internal interface AtomicFileOperations {
  fun openStableDirectory(path: Path): AtomicDirectoryOperations
}

internal interface AtomicDirectoryOperations : AutoCloseable {
  fun verifyCurrent()

  fun requireAbsentOrRegularFile(name: Path)

  fun requireAtomicReplaceSupported()

  fun readRegularFile(name: Path, maxBytes: Int): ByteArray?

  fun createPrivateTempFile(prefix: String, suffix: String): AtomicTemporaryFile

  fun atomicReplace(source: Path, target: Path)

  fun forceDirectory()

  fun tryAcquireExclusiveLock(): AutoCloseable?

  fun openLockFile(name: Path): FileChannel

  fun deleteIfExists(name: Path)
}

internal interface AtomicTemporaryFile : AutoCloseable {
  val fileName: Path

  fun write(bytes: ByteArray)

  fun force()
}

internal object NioAtomicFileOperations : AtomicFileOperations {
  override fun openStableDirectory(path: Path): AtomicDirectoryOperations {
    val logicalPath = path.toAbsolutePath().normalize()
    val opened = openStableAtomicDirectory(logicalPath)
    return NioAtomicDirectoryOperations(logicalPath, opened)
  }
}

private class NioAtomicDirectoryOperations(
  private val logicalPath: Path,
  private val stable: StableDirectoryHandle,
) : AtomicDirectoryOperations {
  override fun verifyCurrent() {
    val reopened = try {
      openStableAtomicDirectory(logicalPath)
    } catch (exception: Exception) {
      throw VerifiedAtomicStateFileException("The atomic state parent changed identity.", exception)
    }
    reopened.use {
      if (!stable.isSameDirectory(reopened)) {
        throw VerifiedAtomicStateFileException("The atomic state parent changed identity.")
      }
    }
  }

  override fun requireAbsentOrRegularFile(name: Path) {
    readAttributes(name)
  }

  override fun requireAtomicReplaceSupported() {
    var source: AtomicTemporaryFile? = null
    var target: AtomicTemporaryFile? = null
    var sourceCleanup: Path? = null
    var targetCleanup: Path? = null
    try {
      val createdSource = createPrivateTempFile(".reqws-atomic-source-", ".probe")
      source = createdSource
      sourceCleanup = createdSource.fileName
      val createdTarget = createPrivateTempFile(".reqws-atomic-target-", ".probe")
      target = createdTarget
      targetCleanup = createdTarget.fileName
      createdSource.write(ATOMIC_REPLACE_SOURCE)
      createdSource.force()
      createdTarget.write(ATOMIC_REPLACE_TARGET)
      createdTarget.force()
      createdSource.close()
      source = null
      createdTarget.close()
      target = null

      atomicReplace(createdSource.fileName, createdTarget.fileName)
      val replaced = readRegularFile(createdTarget.fileName, ATOMIC_REPLACE_SOURCE.size)
      if (
        replaced == null ||
        !replaced.contentEquals(ATOMIC_REPLACE_SOURCE) ||
        readRegularFile(createdSource.fileName, ATOMIC_REPLACE_SOURCE.size) != null
      ) {
        throw VerifiedAtomicStateFileException(
          "The filesystem did not replace the atomic probe target.",
        )
      }
      sourceCleanup = null
    } catch (exception: VerifiedAtomicStateFileException) {
      throw exception
    } catch (exception: Exception) {
      throw VerifiedAtomicStateFileException(
        "The filesystem does not provide verified atomic replacement.",
        exception,
      )
    } finally {
      try {
        source?.close()
      } catch (_: Exception) {
        // Best-effort capability-probe cleanup.
      }
      try {
        target?.close()
      } catch (_: Exception) {
        // Best-effort capability-probe cleanup.
      }
      sourceCleanup?.let(::deleteProbeFile)
      targetCleanup?.let(::deleteProbeFile)
    }
  }

  override fun readRegularFile(name: Path, maxBytes: Int): ByteArray? {
    val childName = child(name)
    val opened = stable.openExistingFile(childName, writable = false) ?: return null
    opened.use {
      val metadata = opened.metadata
      if (metadata.size <= 0L || metadata.size > maxBytes.toLong()) {
        throw VerifiedAtomicStateFileException("The atomic state source has an invalid size.")
      }
      val channel = opened.channel
      val size = channel.size()
      if (size <= 0L || size > maxBytes.toLong() || size > Int.MAX_VALUE.toLong()) {
        throw VerifiedAtomicStateFileException("The atomic state source changed to an invalid size.")
      }
      val buffer = ByteBuffer.allocate(size.toInt())
      while (buffer.hasRemaining()) {
        if (channel.read(buffer) < 0) {
          throw VerifiedAtomicStateFileException(
            "The atomic state source ended before its declared size.",
          )
        }
      }
      if (channel.read(ByteBuffer.allocate(1)) >= 0) {
        throw VerifiedAtomicStateFileException("The atomic state source grew while it was read.")
      }
      val current = stable.openExistingFile(childName, writable = false)
        ?: throw VerifiedAtomicStateFileException("The atomic state source changed identity while read.")
      current.use {
        if (!opened.isSameFile(current)) {
          throw VerifiedAtomicStateFileException("The atomic state source changed identity while read.")
        }
      }
      return buffer.array()
    }
  }

  override fun createPrivateTempFile(prefix: String, suffix: String): AtomicTemporaryFile {
    repeat(MAX_TEMP_FILE_ATTEMPTS) {
      val name = "$prefix${UUID.randomUUID()}$suffix"
      val opened = try {
        stable.createPrivateFile(name)
      } catch (_: FileAlreadyExistsException) {
        return@repeat
      }
      return NioAtomicTemporaryFile(Path.of(name), opened)
    }
    throw VerifiedAtomicStateFileException("Unable to allocate a private atomic state temp file.")
  }

  override fun atomicReplace(source: Path, target: Path) {
    stable.atomicReplace(child(source), child(target))
  }

  override fun forceDirectory() = stable.force()

  override fun tryAcquireExclusiveLock(): AutoCloseable? = stable.tryAcquireExclusiveLock()

  override fun openLockFile(name: Path): FileChannel {
    requireAbsentOrRegularFile(name)
    val opened = stable.openOrCreatePrivateFile(child(name))
    return try {
      opened.detachChannel()
    } catch (failure: Throwable) {
      try {
        opened.close()
      } catch (closeFailure: Throwable) {
        failure.addSuppressed(closeFailure)
      }
      throw failure
    }
  }

  override fun deleteIfExists(name: Path) = stable.deleteFileIfExists(child(name))

  override fun close() = stable.close()

  private fun readAttributes(name: Path): StableFileMetadata? =
    stable.readEntryAttributes(child(name))

  private fun child(name: Path): String {
    val text = name.toString()
    if (
      name.isAbsolute ||
      name.nameCount != 1 ||
      text == "." ||
      text == ".." ||
      name.normalize() != name
    ) {
      throw VerifiedAtomicStateFileException("Atomic state operations require a direct child name.")
    }
    return text
  }

  private fun deleteProbeFile(name: Path) {
    try {
      deleteIfExists(name)
    } catch (_: Exception) {
      // Best-effort capability-probe cleanup.
    }
  }
}

private class NioAtomicTemporaryFile(
  override val fileName: Path,
  private val opened: StableFileHandle,
) : AtomicTemporaryFile {
  private val channel = opened.channel

  override fun write(bytes: ByteArray) {
    channel.position(0L)
    channel.truncate(0L)
    val buffer = ByteBuffer.wrap(bytes)
    while (buffer.hasRemaining()) channel.write(buffer)
  }

  override fun force() = channel.force(true)

  override fun close() = opened.close()
}

private fun openStableAtomicDirectory(logicalPath: Path): StableDirectoryHandle {
  val pathAttributes = Files.readAttributes(
    logicalPath,
    BasicFileAttributes::class.java,
    LinkOption.NOFOLLOW_LINKS,
  )
  val pathIdentity = pathAttributes.fileKey()
  if (!pathAttributes.isDirectory || pathAttributes.isSymbolicLink || pathIdentity == null) {
    throw VerifiedAtomicStateFileException("The atomic state parent must be a real directory.")
  }
  val canonicalPath = logicalPath.toRealPath()
  val stable = StableDirectoryHandle.open(canonicalPath)
  return try {
    val currentAttributes = Files.readAttributes(
      logicalPath,
      BasicFileAttributes::class.java,
      LinkOption.NOFOLLOW_LINKS,
    )
    if (
      !currentAttributes.isDirectory ||
      currentAttributes.isSymbolicLink ||
      currentAttributes.fileKey() == null ||
      currentAttributes.fileKey() != pathIdentity
    ) {
      throw VerifiedAtomicStateFileException("The atomic state parent changed identity.")
    }
    val currentCanonicalPath = logicalPath.toRealPath()
    StableDirectoryHandle.open(currentCanonicalPath).use { current ->
      if (!stable.isSameDirectory(current)) {
        throw VerifiedAtomicStateFileException("The atomic state parent changed identity.")
      }
    }
    stable
  } catch (failure: Throwable) {
    try {
      stable.close()
    } catch (closeFailure: Throwable) {
      failure.addSuppressed(closeFailure)
    }
    throw failure
  }
}

private const val MAX_TEMP_FILE_ATTEMPTS = 128
private val ATOMIC_REPLACE_SOURCE = byteArrayOf(0x51)
private val ATOMIC_REPLACE_TARGET = byteArrayOf(0x52)
