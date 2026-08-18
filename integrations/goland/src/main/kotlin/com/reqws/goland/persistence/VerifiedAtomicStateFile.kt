package com.reqws.goland.persistence

import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.NoSuchFileException
import java.nio.file.OpenOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.PosixFilePermissions

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

  fun read(): T? {
    val parent = file.parent
      ?: throw VerifiedAtomicStateFileException("The atomic state file has no parent directory.")
    try {
      operations.requireRealDirectory(parent)
    } catch (exception: VerifiedAtomicStateFileException) {
      throw exception
    } catch (exception: Exception) {
      throw VerifiedAtomicStateFileException("The atomic state file parent is unsafe.", exception)
    }
    val bytes = try {
      operations.readRegularFile(file, maxBytes)
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

    val parent = file.parent
      ?: throw VerifiedAtomicStateFileException("The atomic state file has no parent directory.")
    try {
      operations.requireRealDirectory(parent)
      operations.requireAbsentOrRegularFile(file)
    } catch (exception: VerifiedAtomicStateFileException) {
      throw exception
    } catch (exception: Exception) {
      throw VerifiedAtomicStateFileException("The atomic state file path is unsafe.", exception)
    }

    var temporary: Path? = null
    try {
      temporary = operations.createPrivateTempFile(parent, ".${file.fileName}.", ".tmp")
      operations.write(temporary, bytes)
      operations.forceFile(temporary)
      operations.atomicReplace(temporary, file)
      temporary = null
      operations.forceDirectory(parent)
      val reloaded = read()
        ?: throw VerifiedAtomicStateFileException("The atomic state file disappeared after replace.")
      if (reloaded != value) {
        throw VerifiedAtomicStateFileException("The atomic state file failed strict read-back verification.")
      }
    } catch (exception: VerifiedAtomicStateFileException) {
      throw exception
    } catch (exception: AtomicMoveNotSupportedException) {
      throw VerifiedAtomicStateFileException("Atomic state replacement is not supported.", exception)
    } catch (exception: Exception) {
      throw VerifiedAtomicStateFileException("Unable to persist the atomic state file.", exception)
    } finally {
      temporary?.let { path ->
        try {
          operations.deleteIfExists(path)
        } catch (_: Exception) {
          // A private temp file may remain for diagnostics, but cleanup failure must not hide the
          // original persistence failure.
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
  fun requireRealDirectory(path: Path)

  fun requireAbsentOrRegularFile(path: Path)

  fun readRegularFile(path: Path, maxBytes: Int): ByteArray?

  fun createPrivateTempFile(parent: Path, prefix: String, suffix: String): Path

  fun write(path: Path, bytes: ByteArray)

  fun forceFile(path: Path)

  fun atomicReplace(source: Path, target: Path)

  fun forceDirectory(path: Path)

  fun deleteIfExists(path: Path)
}

internal object NioAtomicFileOperations : AtomicFileOperations {
  override fun requireRealDirectory(path: Path) {
    val attributes = Files.readAttributes(
      path,
      BasicFileAttributes::class.java,
      LinkOption.NOFOLLOW_LINKS,
    )
    if (!attributes.isDirectory || attributes.isSymbolicLink) {
      throw VerifiedAtomicStateFileException("The atomic state parent must be a real directory.")
    }
  }

  override fun requireAbsentOrRegularFile(path: Path) {
    val attributes = try {
      Files.readAttributes(path, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
    } catch (_: NoSuchFileException) {
      return
    }
    if (!attributes.isRegularFile || attributes.isSymbolicLink) {
      throw VerifiedAtomicStateFileException("The atomic state target must be a regular file.")
    }
  }

  override fun readRegularFile(path: Path, maxBytes: Int): ByteArray? {
    val attributes = try {
      Files.readAttributes(path, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
    } catch (_: NoSuchFileException) {
      return null
    }
    if (!attributes.isRegularFile || attributes.isSymbolicLink) {
      throw VerifiedAtomicStateFileException("The atomic state source must be a regular file.")
    }
    if (attributes.size() <= 0L || attributes.size() > maxBytes.toLong()) {
      throw VerifiedAtomicStateFileException("The atomic state source has an invalid size.")
    }

    val options = setOf<OpenOption>(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)
    FileChannel.open(path, options).use { channel ->
      val size = channel.size()
      if (size <= 0L || size > maxBytes.toLong() || size > Int.MAX_VALUE.toLong()) {
        throw VerifiedAtomicStateFileException("The atomic state source changed to an invalid size.")
      }
      val buffer = ByteBuffer.allocate(size.toInt())
      while (buffer.hasRemaining()) {
        if (channel.read(buffer) < 0) {
          throw VerifiedAtomicStateFileException("The atomic state source ended before its declared size.")
        }
      }
      if (channel.read(ByteBuffer.allocate(1)) >= 0) {
        throw VerifiedAtomicStateFileException("The atomic state source grew while it was read.")
      }
      return buffer.array()
    }
  }

  override fun createPrivateTempFile(parent: Path, prefix: String, suffix: String): Path =
    Files.createTempFile(
      parent,
      prefix,
      suffix,
      PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")),
    )

  override fun write(path: Path, bytes: ByteArray) {
    FileChannel.open(
      path,
      StandardOpenOption.WRITE,
      StandardOpenOption.TRUNCATE_EXISTING,
      LinkOption.NOFOLLOW_LINKS,
    ).use { channel ->
      val buffer = ByteBuffer.wrap(bytes)
      while (buffer.hasRemaining()) channel.write(buffer)
    }
  }

  override fun forceFile(path: Path) {
    FileChannel.open(path, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS).use { channel ->
      channel.force(true)
    }
  }

  override fun atomicReplace(source: Path, target: Path) {
    Files.move(
      source,
      target,
      StandardCopyOption.ATOMIC_MOVE,
      StandardCopyOption.REPLACE_EXISTING,
    )
  }

  override fun forceDirectory(path: Path) {
    FileChannel.open(path, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS).use { channel ->
      channel.force(true)
    }
  }

  override fun deleteIfExists(path: Path) {
    Files.deleteIfExists(path)
  }
}
