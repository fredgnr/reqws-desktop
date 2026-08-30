package com.reqws.goland.persistence

import com.sun.jna.Library
import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.Platform
import java.io.Closeable
import java.io.FileInputStream
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.channels.FileChannel
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicBoolean

/**
 * A local directory descriptor used as the anchor for race-free relative filesystem operations.
 *
 * GoLand wraps the JVM default NIO provider and erases [java.nio.file.SecureDirectoryStream].
 * The local macOS product boundary therefore uses public JNA bindings to the POSIX `openat`
 * family. Unsupported or remote providers fail closed when their path cannot be proven identical
 * to this local descriptor.
 */
internal class StableDirectoryHandle private constructor(
  private val descriptor: PosixDescriptor,
) : AutoCloseable {
  private val nativeStatus = readDescriptorStatus(descriptor.fd)
  internal val nativeIdentity: NativeFileIdentity = nativeStatus.identity

  init {
    if (!nativeStatus.isDirectory) {
      throw IOException("The stable handle does not reference a real directory")
    }
  }

  fun openDirectory(name: String): StableDirectoryHandle {
    val child = directChild(name)
    val fd = checkedDescriptor(
      POSIX.openat(descriptor.fd, child, POSIX_FLAGS.directoryOpen),
      operation = "openat directory",
      entry = child,
    )
    return try {
      StableDirectoryHandle(PosixDescriptor(fd))
    } catch (failure: Throwable) {
      closeDescriptorAfterFailure(fd, failure)
      throw failure
    }
  }

  fun readEntryAttributes(name: String): StableFileMetadata? =
    openExistingFile(name, writable = false)?.use { it.metadata }

  fun openExistingFile(
    name: String,
    writable: Boolean,
  ): StableFileHandle? {
    val child = directChild(name)
    val flags = (if (writable) POSIX_FLAGS.readWrite else POSIX_FLAGS.readOnly) or
      POSIX_FLAGS.noFollow or POSIX_FLAGS.closeOnExec or POSIX_FLAGS.nonBlock
    val fd = POSIX.openat(descriptor.fd, child, flags)
    if (fd < 0 && Native.getLastError() == ERRNO_NO_ENTRY) return null
    return openFileHandle(
      checkedDescriptor(fd, "openat file", child),
      writable = writable,
    )
  }

  fun createPrivateFile(name: String): StableFileHandle {
    val child = directChild(name)
    val fd = checkedDescriptor(
      POSIX.openat(
        descriptor.fd,
        child,
        POSIX_FLAGS.readWrite or POSIX_FLAGS.create or POSIX_FLAGS.exclusive or
          POSIX_FLAGS.noFollow or POSIX_FLAGS.closeOnExec or POSIX_FLAGS.nonBlock,
        PRIVATE_FILE_MODE,
      ),
      operation = "openat private file",
      entry = child,
    )
    try {
      checkedResult(POSIX.fchmod(fd, PRIVATE_FILE_MODE), "fchmod private file", child)
    } catch (failure: Throwable) {
      closeDescriptorAfterFailure(fd, failure)
      throw failure
    }
    return openFileHandle(fd, writable = true)
  }

  fun openOrCreatePrivateFile(name: String): StableFileHandle {
    val child = directChild(name)
    val createFlags = POSIX_FLAGS.readWrite or POSIX_FLAGS.create or POSIX_FLAGS.exclusive or
      POSIX_FLAGS.noFollow or POSIX_FLAGS.closeOnExec or POSIX_FLAGS.nonBlock
    val createdFd = POSIX.openat(descriptor.fd, child, createFlags, PRIVATE_FILE_MODE)
    if (createdFd >= 0) {
      try {
        checkedResult(
          POSIX.fchmod(createdFd, PRIVATE_FILE_MODE),
          "fchmod private file",
          child,
        )
      } catch (failure: Throwable) {
        closeDescriptorAfterFailure(createdFd, failure)
        throw failure
      }
      return openFileHandle(createdFd, writable = true)
    }
    val createError = Native.getLastError()
    if (createError != ERRNO_EXISTS) {
      throw nativeFailure("openat private file", child, createError)
    }
    return openExistingFile(child, writable = true)
      ?: throw NoSuchFileException(child)
  }

  fun atomicReplace(source: String, target: String) {
    val sourceName = directChild(source)
    val targetName = directChild(target)
    checkedResult(
      POSIX.renameat(descriptor.fd, sourceName, descriptor.fd, targetName),
      operation = "renameat",
      entry = sourceName,
    )
  }

  fun deleteFileIfExists(name: String) {
    val child = directChild(name)
    val result = POSIX.unlinkat(descriptor.fd, child, 0)
    if (result == 0) return
    val error = Native.getLastError()
    if (error != ERRNO_NO_ENTRY) throw nativeFailure("unlinkat", child, error)
  }

  fun force() {
    checkedResult(POSIX.fsync(descriptor.fd), "fsync directory")
  }

  /**
   * Tries to lock this directory's native inode, rather than any replaceable child entry.
   *
   * A fresh descriptor is opened for the same directory so the returned lock owns its complete
   * lifetime. Closing the lock descriptor releases the native `flock` without affecting the
   * descriptor used for the stable relative operations.
   */
  fun tryAcquireExclusiveLock(): StableDirectoryLock? {
    val lockFd = checkedDescriptor(
      POSIX.openat(descriptor.fd, CURRENT_DIRECTORY, POSIX_FLAGS.directoryOpen),
      operation = "openat directory lock",
    )
    val lockIdentity = try {
      readDescriptorIdentity(lockFd)
    } catch (failure: Throwable) {
      closeDescriptorAfterFailure(lockFd, failure)
      throw failure
    }
    if (lockIdentity != nativeIdentity) {
      val failure = IOException("The directory lock descriptor changed identity")
      closeDescriptorAfterFailure(lockFd, failure)
      throw failure
    }

    val result = POSIX.flock(lockFd, LOCK_EXCLUSIVE or LOCK_NON_BLOCKING)
    if (result == 0) return StableDirectoryLock(PosixDescriptor(lockFd))

    val error = Native.getLastError()
    if (isLockUnavailable(error)) {
      closeDescriptor(lockFd)
      return null
    }
    val failure = nativeFailure("lock directory", null, error)
    closeDescriptorAfterFailure(lockFd, failure)
    throw failure
  }

  fun isSameDirectory(other: StableDirectoryHandle): Boolean =
    nativeIdentity == other.nativeIdentity

  override fun close() = descriptor.close()

  companion object {
    fun open(path: Path): StableDirectoryHandle {
      if (
        (!Platform.isMac() && !Platform.isLinux()) ||
        !Platform.is64Bit() ||
        (!Platform.isIntel() && !Platform.isARM())
      ) {
        throw UnsupportedOperationException("Stable POSIX directory handles are unavailable")
      }
      val absolute = path.toAbsolutePath().normalize()
      if (!absolute.isAbsolute || absolute.root?.toString() != UNIX_ROOT) {
        throw IOException("A stable directory handle requires an absolute Unix path")
      }
      val pathIdentityBefore = readPathIdentity(absolute)

      var current = checkedDescriptor(
        POSIX.open(UNIX_ROOT, POSIX_FLAGS.directoryOpen),
        operation = "open root directory",
      )
      try {
        for (component in absolute) {
          val name = directChild(component.toString())
          val next = checkedDescriptor(
            POSIX.openat(current, name, POSIX_FLAGS.directoryOpen),
            operation = "openat directory",
            entry = name,
          )
          try {
            closeDescriptor(current)
          } catch (failure: Throwable) {
            current = INVALID_DESCRIPTOR
            closeDescriptorAfterFailure(next, failure)
            throw failure
          }
          current = next
        }
        val descriptorIdentity = readDescriptorIdentity(current)
        val pathIdentityAfter = readPathIdentity(absolute)
        if (descriptorIdentity != pathIdentityBefore || descriptorIdentity != pathIdentityAfter) {
          throw IOException("The routed path does not reference the opened local directory")
        }
        return StableDirectoryHandle(PosixDescriptor(current))
      } catch (failure: Throwable) {
        if (current >= 0) closeDescriptorAfterFailure(current, failure)
        throw failure
      }
    }
  }
}

internal class StableDirectoryLock internal constructor(
  private val descriptor: PosixDescriptor,
) : AutoCloseable {
  override fun close() = descriptor.close()
}

internal class StableFileHandle internal constructor(
  private val descriptor: PosixDescriptor,
  private val channelOwner: Closeable,
  val channel: FileChannel,
  val metadata: StableFileMetadata,
  private val nativeIdentity: NativeFileIdentity,
) : AutoCloseable {
  private val channelOwned = AtomicBoolean(true)

  fun detachChannel(): FileChannel {
    descriptor.close()
    channelOwned.set(false)
    return channel
  }

  fun isSameFile(other: StableFileHandle): Boolean =
    nativeIdentity == other.nativeIdentity

  override fun close() {
    var failure: Throwable? = null
    if (channelOwned.compareAndSet(true, false)) {
      try {
        channelOwner.close()
      } catch (caught: Throwable) {
        failure = caught
      }
    }
    try {
      descriptor.close()
    } catch (caught: Throwable) {
      if (failure == null) failure = caught else failure.addSuppressed(caught)
    }
    failure?.let { throw it }
  }
}

internal class PosixDescriptor(
  val fd: Int,
) : AutoCloseable {
  val bridgePath: String = "/dev/fd/$fd"
  private val open = AtomicBoolean(true)

  override fun close() {
    if (open.compareAndSet(true, false)) closeDescriptor(fd)
  }
}

private fun openFileHandle(fd: Int, writable: Boolean): StableFileHandle {
  val descriptor = PosixDescriptor(fd)
  var channelOwner: Closeable? = null
  return try {
    val status = readDescriptorStatus(descriptor.fd)
    if (!status.isRegularFile) {
      throw IOException("The stable handle does not reference a regular file")
    }
    // java.io deliberately bypasses GoLand's routed NIO provider. The native descriptor has
    // already been opened with NOFOLLOW and validated by fstat; /dev/fd only duplicates it.
    val owner: Closeable
    val channel: FileChannel
    if (writable) {
      val randomAccess = RandomAccessFile(descriptor.bridgePath, "rw")
      channelOwner = randomAccess
      owner = randomAccess
      channel = randomAccess.channel
    } else {
      val input = FileInputStream(descriptor.bridgePath)
      channelOwner = input
      owner = input
      channel = input.channel
    }
    StableFileHandle(
      descriptor = descriptor,
      channelOwner = owner,
      channel = channel,
      metadata = StableFileMetadata(status.size),
      nativeIdentity = status.identity,
    )
  } catch (failure: Throwable) {
    try {
      channelOwner?.close()
    } catch (closeFailure: Throwable) {
      failure.addSuppressed(closeFailure)
    }
    try {
      descriptor.close()
    } catch (closeFailure: Throwable) {
      failure.addSuppressed(closeFailure)
    }
    throw failure
  }
}

internal data class StableFileMetadata(
  val size: Long,
)

internal data class NativeFileIdentity(
  val device: Long,
  val inode: Long,
)

private data class NativeFileStatus(
  val identity: NativeFileIdentity,
  val mode: Int,
  val size: Long,
) {
  val isDirectory: Boolean
    get() = mode and FILE_TYPE_MASK == FILE_TYPE_DIRECTORY

  val isRegularFile: Boolean
    get() = mode and FILE_TYPE_MASK == FILE_TYPE_REGULAR
}

private fun readDescriptorIdentity(fd: Int): NativeFileIdentity =
  readDescriptorStatus(fd).identity

private fun readDescriptorStatus(fd: Int): NativeFileStatus {
  val stat = Memory(NATIVE_STAT_BUFFER_BYTES)
  return try {
    stat.clear()
    checkedResult(POSIX.fstat(fd, stat), "fstat descriptor")
    when {
      Platform.isMac() -> NativeFileStatus(
        identity = NativeFileIdentity(
          device = Integer.toUnsignedLong(stat.getInt(MAC_STAT_DEVICE_OFFSET)),
          inode = stat.getLong(STAT_INODE_OFFSET),
        ),
        mode = stat.getShort(MAC_STAT_MODE_OFFSET).toInt() and 0xFFFF,
        size = stat.getLong(MAC_STAT_SIZE_OFFSET),
      )
      Platform.isLinux() -> NativeFileStatus(
        identity = NativeFileIdentity(
          device = stat.getLong(LINUX_STAT_DEVICE_OFFSET),
          inode = stat.getLong(STAT_INODE_OFFSET),
        ),
        mode = stat.getInt(linuxStatModeOffset()),
        size = stat.getLong(LINUX_STAT_SIZE_OFFSET),
      )
      else -> throw UnsupportedOperationException("Stable POSIX identities are unavailable")
    }
  } finally {
    stat.close()
  }
}

private fun linuxStatModeOffset(): Long = when {
  Platform.isIntel() -> LINUX_INTEL_STAT_MODE_OFFSET
  Platform.isARM() -> LINUX_ARM_STAT_MODE_OFFSET
  else -> throw UnsupportedOperationException("Stable POSIX identities are unavailable")
}

private fun readPathIdentity(path: Path): NativeFileIdentity {
  val uri = path.toUri()
  if (!uri.scheme.equals("file", ignoreCase = true) || uri.authority != null) {
    throw IOException("A stable directory handle requires a local file URI")
  }
  val attributes = Files.readAttributes(
    path,
    "unix:dev,ino",
    LinkOption.NOFOLLOW_LINKS,
  )
  val device = (attributes["dev"] as? Number)?.toLong()
    ?: throw IOException("The routed path has no Unix device identity")
  val inode = (attributes["ino"] as? Number)?.toLong()
    ?: throw IOException("The routed path has no Unix inode identity")
  return NativeFileIdentity(device = device, inode = inode)
}

private fun directChild(name: String): String {
  if (
    name.isEmpty() ||
    name == "." ||
    name == ".." ||
    '/' in name ||
    '\u0000' in name
  ) {
    throw IllegalArgumentException("Stable directory operations require a direct child name")
  }
  return name
}

private fun checkedDescriptor(
  fd: Int,
  operation: String,
  entry: String? = null,
): Int {
  if (fd >= 0) return fd
  throw nativeFailure(operation, entry, Native.getLastError())
}

private fun checkedResult(
  result: Int,
  operation: String,
  entry: String? = null,
) {
  if (result == 0) return
  throw nativeFailure(operation, entry, Native.getLastError())
}

private fun nativeFailure(
  operation: String,
  entry: String?,
  error: Int,
): IOException = when (error) {
  ERRNO_NO_ENTRY -> NoSuchFileException(entry ?: operation)
  ERRNO_EXISTS -> FileAlreadyExistsException(entry ?: operation)
  else -> IOException("$operation failed (errno=$error)")
}

private fun isLockUnavailable(error: Int): Boolean = when {
  Platform.isMac() -> error == MAC_ERRNO_WOULD_BLOCK
  Platform.isLinux() -> error == LINUX_ERRNO_WOULD_BLOCK
  else -> false
}

private fun closeDescriptor(fd: Int) {
  if (POSIX.close(fd) != 0) {
    throw nativeFailure("close descriptor", null, Native.getLastError())
  }
}

private fun closeDescriptorAfterFailure(fd: Int, failure: Throwable) {
  try {
    closeDescriptor(fd)
  } catch (closeFailure: Throwable) {
    failure.addSuppressed(closeFailure)
  }
}

private interface PosixLibC : Library {
  fun open(path: String, flags: Int, vararg mode: Any): Int

  fun openat(directoryFd: Int, path: String, flags: Int, vararg mode: Any): Int

  fun close(fd: Int): Int

  fun fchmod(fd: Int, mode: Int): Int

  fun fstat(fd: Int, stat: Memory): Int

  fun fsync(fd: Int): Int

  fun flock(fd: Int, operation: Int): Int

  fun renameat(sourceDirectoryFd: Int, source: String, targetDirectoryFd: Int, target: String): Int

  fun unlinkat(directoryFd: Int, path: String, flags: Int): Int
}

private data class PosixFlags(
  val readOnly: Int,
  val readWrite: Int,
  val create: Int,
  val exclusive: Int,
  val noFollow: Int,
  val directory: Int,
  val closeOnExec: Int,
  val nonBlock: Int,
) {
  val directoryOpen: Int
    get() = readOnly or noFollow or directory or closeOnExec
}

private val POSIX: PosixLibC by lazy {
  if (!Platform.isMac() && !Platform.isLinux()) {
    throw UnsupportedOperationException("Stable POSIX directory handles are unavailable")
  }
  Native.load(Platform.C_LIBRARY_NAME, PosixLibC::class.java)
}
private val POSIX_FLAGS: PosixFlags by lazy {
  when {
    Platform.isMac() -> PosixFlags(
      readOnly = 0,
      readWrite = 0x0002,
      create = 0x0200,
      exclusive = 0x0800,
      noFollow = 0x0100,
      directory = 0x100000,
      closeOnExec = 0x1000000,
      nonBlock = 0x0004,
    )
    Platform.isLinux() -> PosixFlags(
      readOnly = 0,
      readWrite = 0x0002,
      create = 0x0040,
      exclusive = 0x0080,
      noFollow = 0x20000,
      directory = 0x10000,
      closeOnExec = 0x80000,
      nonBlock = 0x0800,
    )
    else -> throw UnsupportedOperationException("Stable POSIX directory handles are unavailable")
  }
}

private const val UNIX_ROOT = "/"
private const val CURRENT_DIRECTORY = "."
private const val PRIVATE_FILE_MODE = 0x180 // 0600
private const val ERRNO_NO_ENTRY = 2
private const val ERRNO_EXISTS = 17
private const val MAC_ERRNO_WOULD_BLOCK = 35
private const val LINUX_ERRNO_WOULD_BLOCK = 11
private const val LOCK_EXCLUSIVE = 0x02
private const val LOCK_NON_BLOCKING = 0x04
private const val INVALID_DESCRIPTOR = -1
private const val NATIVE_STAT_BUFFER_BYTES = 512L
private const val MAC_STAT_DEVICE_OFFSET = 0L
private const val LINUX_STAT_DEVICE_OFFSET = 0L
private const val STAT_INODE_OFFSET = 8L
private const val MAC_STAT_MODE_OFFSET = 4L
private const val LINUX_INTEL_STAT_MODE_OFFSET = 24L
private const val LINUX_ARM_STAT_MODE_OFFSET = 16L
private const val MAC_STAT_SIZE_OFFSET = 96L
private const val LINUX_STAT_SIZE_OFFSET = 48L
private const val FILE_TYPE_MASK = 0xF000
private const val FILE_TYPE_DIRECTORY = 0x4000
private const val FILE_TYPE_REGULAR = 0x8000
