package com.reqws.goland.manifest

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.NoSuchFileException
import java.nio.file.OpenOption
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest

class ManifestReader {
  fun read(projectRoot: Path): ManifestSnapshot {
    val absoluteProjectRoot = projectRoot.toAbsolutePath().normalize()
    val canonicalProjectRoot = canonicalProjectRoot(absoluteProjectRoot)
    val manifestPath = absoluteProjectRoot.resolve(MANIFEST_RELATIVE_PATH).normalize()
    val bytes = readRegularFile(manifestPath, canonicalProjectRoot)
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

  private fun canonicalProjectRoot(projectRoot: Path): Path {
    return try {
      val canonical = projectRoot.toRealPath()
      if (!Files.isDirectory(canonical)) {
        throw ManifestException(ManifestErrorCode.WORKSPACE_ROOT_MISMATCH, field = "rootPath")
      }
      canonical
    } catch (exception: ManifestException) {
      throw exception
    } catch (_: IOException) {
      throw ManifestException(ManifestErrorCode.WORKSPACE_ROOT_MISMATCH, field = "rootPath")
    }
  }

  private fun readRegularFile(manifestPath: Path, canonicalProjectRoot: Path): ByteArray {
    val attributes = try {
      Files.readAttributes(
        manifestPath,
        BasicFileAttributes::class.java,
        LinkOption.NOFOLLOW_LINKS,
      )
    } catch (_: NoSuchFileException) {
      throw ManifestException(ManifestErrorCode.MANIFEST_NOT_FOUND)
    } catch (_: IOException) {
      throw ManifestException(ManifestErrorCode.MANIFEST_NOT_REGULAR_FILE)
    }
    if (attributes.isSymbolicLink || !attributes.isRegularFile) {
      throw ManifestException(ManifestErrorCode.MANIFEST_NOT_REGULAR_FILE)
    }
    if (attributes.size() > ManifestParser.MAX_MANIFEST_BYTES) {
      throw ManifestException(ManifestErrorCode.MANIFEST_TOO_LARGE)
    }

    val canonicalManifest = try {
      // The target itself was already lstat-ed above. Follow parent links here
      // so both macOS /var -> /private/var aliases and a malicious .reqws
      // parent symlink are evaluated against the real project root.
      manifestPath.toRealPath()
    } catch (_: IOException) {
      throw ManifestException(ManifestErrorCode.MANIFEST_NOT_REGULAR_FILE)
    }
    if (!canonicalManifest.startsWith(canonicalProjectRoot)) {
      throw ManifestException(ManifestErrorCode.MANIFEST_NOT_REGULAR_FILE)
    }

    val options = setOf<OpenOption>(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)
    return try {
      // Open the already-contained canonical path. Parent-link replacement
      // after validation cannot redirect this read outside the project root.
      Files.newByteChannel(canonicalManifest, options).use { channel ->
        if (channel.size() > ManifestParser.MAX_MANIFEST_BYTES) {
          throw ManifestException(ManifestErrorCode.MANIFEST_TOO_LARGE)
        }
        val output = ByteArrayOutputStream(attributes.size().toInt())
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
    } catch (exception: ManifestException) {
      throw exception
    } catch (_: NoSuchFileException) {
      throw ManifestException(ManifestErrorCode.MANIFEST_NOT_FOUND)
    } catch (_: IOException) {
      throw ManifestException(ManifestErrorCode.MANIFEST_NOT_REGULAR_FILE)
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

  companion object {
    val MANIFEST_RELATIVE_PATH: Path = Path.of(".reqws", "workspace.json")
  }
}
