package com.reqws.goland.loading.contract

import com.reqws.goland.manifest.ManifestReader
import com.reqws.goland.manifest.ManifestSnapshot
import com.reqws.goland.manifest.RepositoryAvailability
import com.reqws.goland.persistence.AtomicStateCodec
import com.reqws.goland.persistence.VerifiedAtomicStateFile
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest

data class BoundDirectory(val path: Path, val fileKey: String)

data class VerifiedBinding(
  val workspaceId: String,
  val bindingId: String,
  val workspaceRoot: Path,
  val shell: Path,
  val directories: List<BoundDirectory>,
) {
  val projectFile: Path get() = shell.resolve("reqws-project.json")
  fun verifyCurrent() {
    directories.forEach { expected ->
      require(boundDirectory(expected.path) == expected) { "Binding directory identity changed." }
    }
  }
}

data class LoadingSnapshot(
  val manifest: ManifestSnapshot,
  val binding: VerifiedBinding,
  val project: GoLandProject,
  val projectBytes: List<Byte>,
  val digest: String,
) {
  val loadedIds: Set<String> = resolveGoLandSelection(manifest.manifest.repositories, project.selection)
    .mapTo(linkedSetOf()) { it.catalogRepositoryId }
  val loadedRepositories get() = manifest.repositories.filter { it.repository.catalogRepositoryId in loadedIds }
}

internal fun boundDirectory(path: Path): BoundDirectory {
  val attributes = Files.readAttributes(path, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
  require(attributes.isDirectory && !attributes.isSymbolicLink && attributes.fileKey() != null)
  require(path.toRealPath() == path) { "Binding contains a symbolic-link ancestor." }
  return BoundDirectory(path, attributes.fileKey().toString())
}

/** Reads A / manifest / B / manifest, retrying complete candidates rather than inventing emptiness. */
internal class LoadingSnapshotReader(private val manifestReader: ManifestReader = ManifestReader()) {
  fun read(projectBase: Path): LoadingSnapshot {
    val shell = projectBase.toAbsolutePath().normalize().toRealPath()
    require(shell.fileName.toString() == "goland" && shell.parent.fileName.toString() == "ide" &&
      shell.parent.parent.fileName.toString() == ".reqws") { "Not the fixed ReqWS GoLand entry." }
    // A symlink to a real shell is not a second entry point.
    require(projectBase.toAbsolutePath().normalize() == shell ||
      (projectBase.toString().startsWith("/tmp/") || projectBase.toString().startsWith("/var/")) && shell.toString() == "/private" + projectBase.toAbsolutePath().normalize())
    val root = shell.parent.parent.parent
    repeat(3) {
      val directories = listOf(root, shell.parent.parent, shell.parent, shell).map(::boundDirectory)
      val first = readBytes(shell)
      val config = GoLandProjectParser.parse(first)
      val manifest = manifestReader.read(root)
      val binding = VerifiedBinding(config.workspaceId, config.bindingId, root, shell, directories)
      require(config.workspaceId == manifest.manifest.id) { "Binding workspace ID does not match manifest." }
      val second = readBytes(shell)
      val confirmation = manifestReader.read(root)
      binding.verifyCurrent()
      if (first.contentEquals(second) && manifest.digestSha256 == confirmation.digestSha256) {
        // Only plain independent Git directories are supported. Missing remains requested.
        val requestedIds = resolveGoLandSelection(manifest.manifest.repositories, config.selection).mapTo(hashSetOf()) { it.catalogRepositoryId }
        manifest.repositories.filter { it.availability == RepositoryAvailability.PRESENT && it.repository.catalogRepositoryId in requestedIds }.forEach { repository ->
          require(repository.path == repository.canonicalPath && !Files.isSymbolicLink(repository.path))
          val git = Files.readAttributes(repository.path.resolve(".git"), BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
          require(git.isDirectory && !git.isSymbolicLink) { "Repository is not an independent Git directory." }
          require(!repository.path.startsWith(shell) && !shell.startsWith(repository.path))
        }
        val material = manifest.digestSha256 + ":roots-v1:" + directories.joinToString { it.fileKey }
        val digest = MessageDigest.getInstance("SHA-256").digest(first + material.toByteArray())
          .joinToString("") { "%02x".format(it.toInt() and 0xff) }
        return LoadingSnapshot(manifest, binding, config, first.toList(), digest)
      }
    }
    throw BindingException("The binding or manifest changed during all bounded reads.")
  }

  fun verifyCurrent(snapshot: LoadingSnapshot) {
    snapshot.binding.verifyCurrent()
    require(readBytes(snapshot.binding.shell).toList() == snapshot.projectBytes) { "Selection changed before apply." }
    require(manifestReader.read(snapshot.binding.workspaceRoot).digestSha256 == snapshot.manifest.digestSha256) {
      "Manifest changed before apply."
    }
  }

  private fun readBytes(shell: Path): ByteArray = VerifiedAtomicStateFile(
    shell.resolve("reqws-project.json"), GoLandProjectParser.MAX_BYTES,
    object : AtomicStateCodec<List<Byte>> {
      override fun encode(value: List<Byte>) = value.toByteArray()
      override fun decode(bytes: ByteArray) = bytes.toList()
    },
  ).read()?.toByteArray() ?: throw BindingException("The GoLand binding is missing.")
}
