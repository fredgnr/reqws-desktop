package com.reqws.goland.manifest

import com.reqws.goland.persistence.StableDirectoryHandle
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest

class ManifestReader {
  private val beforeManifestChannelOpen: () -> Unit

  constructor() {
    beforeManifestChannelOpen = {}
  }

  internal constructor(beforeManifestChannelOpen: () -> Unit) {
    this.beforeManifestChannelOpen = beforeManifestChannelOpen
  }

  fun read(projectRoot: Path): ManifestSnapshot {
    val absoluteProjectRoot = projectRoot.toAbsolutePath().normalize()
    val projectRootIdentity = canonicalProjectRoot(absoluteProjectRoot)
    val canonicalProjectRoot = projectRootIdentity.path
    val manifestPath = absoluteProjectRoot.resolve(MANIFEST_RELATIVE_PATH).normalize()
    val bytes = readRegularFile(projectRootIdentity)
    val digest = sha256(bytes)
    val manifest = try {
      ManifestParser.parse(bytes)
    } catch (exception: ManifestException) {
      throw exception.withDigest(digest)
    }
    return try {
      validate(manifest, manifestPath, canonicalProjectRoot, digest)
    } catch (exception: ManifestException) {
      throw if (exception.digestSha256 == null) exception.withDigest(digest) else exception
    }
  }

  private fun canonicalProjectRoot(projectRoot: Path): DirectoryIdentity {
    return try {
      val canonical = projectRoot.toRealPath()
      val attributes = Files.readAttributes(
        canonical,
        BasicFileAttributes::class.java,
        LinkOption.NOFOLLOW_LINKS,
      )
      val fileKey = attributes.fileKey()
      if (attributes.isSymbolicLink || !attributes.isDirectory || fileKey == null) {
        throw ManifestException(ManifestErrorCode.WORKSPACE_ROOT_MISMATCH, field = "rootPath")
      }
      DirectoryIdentity(path = canonical, fileKey = fileKey)
    } catch (exception: ManifestException) {
      throw exception
    } catch (_: IOException) {
      throw ManifestException(ManifestErrorCode.WORKSPACE_ROOT_MISMATCH, field = "rootPath")
    }
  }

  private fun readRegularFile(projectRootIdentity: DirectoryIdentity): ByteArray {
    return try {
      StableDirectoryHandle.open(projectRootIdentity.path).use { rootHandle ->
        verifyProjectRootHandle(rootHandle, projectRootIdentity)
        val bytes = rootHandle.openDirectory(".reqws").use { manifestDirectory ->
          val metadata = manifestDirectory.readEntryAttributes("workspace.json")
            ?: throw NoSuchFileException("workspace.json")
          if (metadata.size > ManifestParser.MAX_MANIFEST_BYTES) {
            throw ManifestException(ManifestErrorCode.MANIFEST_TOO_LARGE)
          }

          beforeManifestChannelOpen()
          val opened = manifestDirectory.openExistingFile("workspace.json", writable = false)
            ?: throw NoSuchFileException("workspace.json")
          opened.use {
            val channel = opened.channel
            if (channel.size() > ManifestParser.MAX_MANIFEST_BYTES) {
              throw ManifestException(ManifestErrorCode.MANIFEST_TOO_LARGE)
            }
            val output = ByteArrayOutputStream(metadata.size.toInt())
            val buffer = ByteBuffer.allocate(16 * 1024)
            var total = 0
            while (true) {
              val count = channel.read(buffer)
              if (count < 0) break
              if (count == 0) continue
              total += count
              if (total > ManifestParser.MAX_MANIFEST_BYTES) {
                throw ManifestException(ManifestErrorCode.MANIFEST_TOO_LARGE)
              }
              output.write(buffer.array(), 0, count)
              buffer.clear()
            }
            output.toByteArray()
          }
        }
        verifyProjectRootHandle(rootHandle, projectRootIdentity)
        bytes
      }
    } catch (exception: ManifestException) {
      throw exception
    } catch (_: NoSuchFileException) {
      throw ManifestException(ManifestErrorCode.MANIFEST_NOT_FOUND)
    } catch (_: IOException) {
      throw ManifestException(ManifestErrorCode.MANIFEST_NOT_REGULAR_FILE)
    } catch (_: UnsupportedOperationException) {
      throw ManifestException(ManifestErrorCode.MANIFEST_NOT_REGULAR_FILE)
    } catch (_: SecurityException) {
      throw ManifestException(ManifestErrorCode.MANIFEST_NOT_REGULAR_FILE)
    } catch (_: IllegalArgumentException) {
      throw ManifestException(ManifestErrorCode.MANIFEST_NOT_REGULAR_FILE)
    }
  }

  private fun verifyProjectRootHandle(
    handle: StableDirectoryHandle,
    expected: DirectoryIdentity,
  ) {
    val current = Files.readAttributes(
      expected.path,
      BasicFileAttributes::class.java,
      LinkOption.NOFOLLOW_LINKS,
    )
    if (
      !current.isDirectory ||
      current.isSymbolicLink ||
      current.fileKey() == null ||
      current.fileKey() != expected.fileKey
    ) {
      throw ManifestException(ManifestErrorCode.WORKSPACE_ROOT_MISMATCH, field = "rootPath")
    }
    StableDirectoryHandle.open(expected.path).use { currentHandle ->
      if (!handle.isSameDirectory(currentHandle)) {
        throw ManifestException(ManifestErrorCode.WORKSPACE_ROOT_MISMATCH, field = "rootPath")
      }
    }
  }

  private fun validate(
    manifest: WorkspaceManifest,
    manifestPath: Path,
    canonicalProjectRoot: Path,
    digest: String,
  ): ManifestSnapshot {
    val manifestRoot = try {
      Path.of(manifest.rootPath).toRealPath()
    } catch (_: Exception) {
      throw ManifestException(ManifestErrorCode.WORKSPACE_ROOT_MISMATCH, field = "rootPath")
    }
    if (manifestRoot != canonicalProjectRoot) {
      throw ManifestException(ManifestErrorCode.WORKSPACE_ROOT_MISMATCH, field = "rootPath")
    }

    val diagnostics = ArrayList<ManifestDiagnostic>()
    val canonicalRepositories = HashSet<Path>()
    val repositories = manifest.repositories.mapIndexed { index, repository ->
      val candidate = canonicalProjectRoot.resolve(repository.relativePath).normalize()
      if (!candidate.startsWith(canonicalProjectRoot) || candidate == canonicalProjectRoot) {
        throw ManifestException(
          ManifestErrorCode.REPOSITORY_PATH_ESCAPE,
          field = "repositories[$index].relativePath",
        )
      }
      if (!Files.exists(candidate, LinkOption.NOFOLLOW_LINKS)) {
        diagnostics.add(
          ManifestDiagnostic(
            code = ManifestErrorCode.REPOSITORY_MISSING,
            severity = ManifestDiagnosticSeverity.WARNING,
            repositoryIndex = index,
          ),
        )
        ResolvedRepository(
          repository = repository,
          path = candidate,
          canonicalPath = null,
          availability = RepositoryAvailability.MISSING,
        )
      } else {
        val canonicalRepository = try {
          candidate.toRealPath()
        } catch (_: IOException) {
          throw ManifestException(
            ManifestErrorCode.REPOSITORY_PATH_INVALID,
            field = "repositories[$index].relativePath",
          )
        }
        if (
          canonicalRepository == canonicalProjectRoot ||
          !canonicalRepository.startsWith(canonicalProjectRoot)
        ) {
          throw ManifestException(
            ManifestErrorCode.REPOSITORY_PATH_ESCAPE,
            field = "repositories[$index].relativePath",
          )
        }
        if (!Files.isDirectory(canonicalRepository)) {
          throw ManifestException(
            ManifestErrorCode.REPOSITORY_PATH_INVALID,
            field = "repositories[$index].relativePath",
          )
        }
        if (!canonicalRepositories.add(canonicalRepository)) {
          throw ManifestException(
            ManifestErrorCode.REPOSITORY_DUPLICATE,
            field = "repositories[$index].relativePath",
          )
        }
        ResolvedRepository(
          repository = repository,
          path = candidate,
          canonicalPath = canonicalRepository,
          availability = RepositoryAvailability.PRESENT,
        )
      }
    }

    return ManifestSnapshot(
      manifest = manifest,
      manifestPath = manifestPath,
      canonicalProjectRoot = canonicalProjectRoot,
      repositories = repositories,
      digestSha256 = digest,
      diagnostics = diagnostics,
    )
  }

  private fun sha256(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256")
      .digest(bytes)
      .joinToString(separator = "") { byte -> "%02x".format(byte) }

  private data class DirectoryIdentity(
    val path: Path,
    val fileKey: Any,
  )

  companion object {
    val MANIFEST_RELATIVE_PATH: Path = Path.of(".reqws", "workspace.json")
  }
}
