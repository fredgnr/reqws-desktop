package com.reqws.goland.manifest

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path

class ManifestReaderTest {
  @Rule
  @JvmField
  val temporaryFolder = TemporaryFolder()

  private val reader = ManifestReader()

  @Test
  fun `reads present and missing repositories without creating directories`() {
    val root = newRoot("workspace")
    Files.createDirectory(root.resolve("repo-present"))
    writeManifest(
      root,
      manifestJson(root, repositories = listOf("repo-present", "repo-missing")),
    )

    val snapshot = reader.read(root)

    assertEquals(RepositoryAvailability.PRESENT, snapshot.repositories[0].availability)
    assertEquals(RepositoryAvailability.MISSING, snapshot.repositories[1].availability)
    assertNull(snapshot.repositories[1].canonicalPath)
    assertEquals(1, snapshot.missingRepositoryCount)
    assertEquals(ManifestErrorCode.REPOSITORY_MISSING, snapshot.diagnostics.single().code)
    assertFalse(Files.exists(root.resolve("repo-missing")))
  }

  @Test
  fun `hashes the exact original bytes including trailing whitespace`() {
    val root = newRoot("digest-workspace")
    val firstBytes = manifestJson(root).toByteArray(StandardCharsets.UTF_8)
    writeManifest(root, firstBytes)
    val first = reader.read(root)

    val secondBytes = firstBytes + '\n'.code.toByte()
    writeManifest(root, secondBytes)
    val second = reader.read(root)

    assertEquals(sha256(firstBytes), first.digestSha256)
    assertEquals(sha256(secondBytes), second.digestSha256)
    assertNotEquals(first.digestSha256, second.digestSha256)
  }

  @Test
  fun `rejects a missing manifest with a stable code`() {
    val root = newRoot("missing-manifest")

    assertEquals(ManifestErrorCode.MANIFEST_NOT_FOUND, expectFailure { reader.read(root) }.code)
  }

  @Test
  fun `rejects a manifest symlink and a manifest directory`() {
    val symlinkRoot = newRoot("symlink-manifest")
    val target = temporaryFolder.newFile("manifest-target.json").toPath()
    Files.writeString(target, manifestJson(symlinkRoot))
    Files.createDirectories(symlinkRoot.resolve(".reqws"))
    Files.createSymbolicLink(symlinkRoot.resolve(ManifestReader.MANIFEST_RELATIVE_PATH), target)

    assertEquals(
      ManifestErrorCode.MANIFEST_NOT_REGULAR_FILE,
      expectFailure { reader.read(symlinkRoot) }.code,
    )

    val directoryRoot = newRoot("directory-manifest")
    Files.createDirectories(directoryRoot.resolve(ManifestReader.MANIFEST_RELATIVE_PATH))
    assertEquals(
      ManifestErrorCode.MANIFEST_NOT_REGULAR_FILE,
      expectFailure { reader.read(directoryRoot) }.code,
    )
  }

  @Test
  fun `rejects a manifest reached through a parent symlink outside the root`() {
    val root = newRoot("manifest-parent-escape")
    val outside = newRoot("outside-manifest-parent")
    Files.writeString(outside.resolve("workspace.json"), manifestJson(root))
    Files.createSymbolicLink(root.resolve(".reqws"), outside)

    assertEquals(
      ManifestErrorCode.MANIFEST_NOT_REGULAR_FILE,
      expectFailure { reader.read(root) }.code,
    )
  }

  @Test
  fun `rejects oversized files before parsing`() {
    val root = newRoot("oversized")
    val bytes = ByteArray(ManifestParser.MAX_MANIFEST_BYTES + 1) { ' '.code.toByte() }
    writeManifest(root, bytes)

    assertEquals(ManifestErrorCode.MANIFEST_TOO_LARGE, expectFailure { reader.read(root) }.code)
  }

  @Test
  fun `accepts a valid manifest whose raw file is exactly one MiB`() {
    val root = newRoot("exact-limit")
    val json = manifestJson(root).toByteArray(StandardCharsets.UTF_8)
    val bytes = json + ByteArray(ManifestParser.MAX_MANIFEST_BYTES - json.size) { ' '.code.toByte() }
    writeManifest(root, bytes)

    assertEquals(64, reader.read(root).digestSha256.length)
  }

  @Test
  fun `rejects a manifest bound to another canonical project root`() {
    val root = newRoot("expected-root")
    val other = newRoot("other-root")
    writeManifest(root, manifestJson(other))

    assertEquals(
      ManifestErrorCode.WORKSPACE_ROOT_MISMATCH,
      expectFailure { reader.read(root) }.code,
    )
  }

  @Test
  fun `compares project roots by canonical identity across a root symlink`() {
    val root = newRoot("canonical-root")
    val alias = temporaryFolder.root.toPath().resolve("canonical-root-alias")
    Files.createSymbolicLink(alias, root)
    writeManifest(root, manifestJson(alias))

    val snapshot = reader.read(root)

    assertEquals(root.toRealPath(), snapshot.canonicalProjectRoot)
    assertEquals(root.toRealPath(), Path.of(snapshot.manifest.rootPath).toRealPath())
  }

  @Test
  fun `rejects a repository symlink that escapes the canonical root`() {
    val root = newRoot("repository-escape")
    val outside = newRoot("outside-repository")
    Files.createSymbolicLink(root.resolve("repo-link"), outside)
    writeManifest(root, manifestJson(root, repositories = listOf("repo-link")))

    assertEquals(
      ManifestErrorCode.REPOSITORY_PATH_ESCAPE,
      expectFailure { reader.read(root) }.code,
    )
  }

  @Test
  fun `accepts a repository symlink whose canonical target stays inside the root`() {
    val root = newRoot("repository-contained-link")
    Files.createDirectory(root.resolve("actual-repository"))
    Files.createSymbolicLink(root.resolve("repo-link"), root.resolve("actual-repository"))
    writeManifest(root, manifestJson(root, repositories = listOf("repo-link")))

    val resolved = reader.read(root).repositories.single()

    assertEquals(RepositoryAvailability.PRESENT, resolved.availability)
    assertEquals(root.resolve("actual-repository").toRealPath(), resolved.canonicalPath)
  }

  @Test
  fun `rejects a repository symlink that resolves to the workspace root`() {
    val root = newRoot("repository-root-link")
    Files.createSymbolicLink(root.resolve("repo-link"), root)
    writeManifest(root, manifestJson(root, repositories = listOf("repo-link")))

    assertEquals(
      ManifestErrorCode.REPOSITORY_PATH_ESCAPE,
      expectFailure { reader.read(root) }.code,
    )
  }

  @Test
  fun `rejects distinct repository names that resolve to one canonical directory`() {
    val root = newRoot("repository-alias-duplicate")
    val target = Files.createDirectory(root.resolve("actual-repository"))
    Files.createSymbolicLink(root.resolve("repo-one"), target)
    Files.createSymbolicLink(root.resolve("repo-two"), target)
    writeManifest(root, manifestJson(root, repositories = listOf("repo-one", "repo-two")))

    assertEquals(
      ManifestErrorCode.REPOSITORY_DUPLICATE,
      expectFailure { reader.read(root) }.code,
    )
  }

  @Test
  fun `rejects an existing repository path that is not a directory`() {
    val root = newRoot("repository-file")
    Files.writeString(root.resolve("repo-file"), "not a directory")
    writeManifest(root, manifestJson(root, repositories = listOf("repo-file")))

    assertEquals(
      ManifestErrorCode.REPOSITORY_PATH_INVALID,
      expectFailure { reader.read(root) }.code,
    )
  }

  @Test
  fun `attaches an original-byte digest to parse and validation failures`() {
    val parseRoot = newRoot("parse-digest")
    val invalid = "{".toByteArray()
    writeManifest(parseRoot, invalid)
    assertEquals(sha256(invalid), expectFailure { reader.read(parseRoot) }.digestSha256)

    val mismatchRoot = newRoot("validation-digest")
    val other = newRoot("validation-other")
    val mismatch = manifestJson(other).toByteArray()
    writeManifest(mismatchRoot, mismatch)
    assertEquals(sha256(mismatch), expectFailure { reader.read(mismatchRoot) }.digestSha256)
  }

  @Test
  fun `snapshot and diagnostic strings do not expose repository URLs`() {
    val root = newRoot("redacted")
    Files.createDirectory(root.resolve("repo"))
    writeManifest(root, manifestJson(root, repositories = listOf("repo")))

    val snapshot = reader.read(root)

    assertFalse(snapshot.toString().contains("example.test"))
    assertFalse(snapshot.repositories.single().toString().contains("example.test"))
    assertTrue(snapshot.diagnostics.isEmpty())
  }

  private fun newRoot(name: String): Path = temporaryFolder.newFolder(name).toPath()

  private fun writeManifest(root: Path, content: String) =
    writeManifest(root, content.toByteArray(StandardCharsets.UTF_8))

  private fun writeManifest(root: Path, bytes: ByteArray) {
    val path = root.resolve(ManifestReader.MANIFEST_RELATIVE_PATH)
    Files.createDirectories(path.parent)
    Files.write(path, bytes)
  }

  private fun manifestJson(root: Path, repositories: List<String> = emptyList()): String {
    val repositoryJson = repositories.mapIndexed { index, name ->
      """
        {
          "catalogRepositoryId": "repo_$index",
          "name": ${jsonString(name)},
          "url": "https://example.test/team/repository.git",
          "defaultBranch": "main",
          "relativePath": ${jsonString(name)}
        }
      """.trimIndent()
    }.joinToString(",")
    return """
      {
        "schemaVersion": 1,
        "id": "ws_test",
        "name": "Test workspace",
        "featureBranch": "feature/test",
        "rootPath": ${jsonString(root.toString())},
        "workspaceFilePath": ${jsonString(root.resolveSibling("test.code-workspace").toString())},
        "repositories": [$repositoryJson],
        "createdAt": "2026-08-14T00:00:00.000Z",
        "updatedAt": "2026-08-14T00:00:00.000Z"
      }
    """.trimIndent()
  }

  private fun jsonString(value: String): String =
    "\"${value.replace("\\", "\\\\").replace("\"", "\\\"")}\""

  private fun sha256(bytes: ByteArray): String =
    java.security.MessageDigest.getInstance("SHA-256")
      .digest(bytes)
      .joinToString("") { byte -> "%02x".format(byte) }

  private fun expectFailure(block: () -> Unit): ManifestException {
    try {
      block()
    } catch (exception: ManifestException) {
      return exception
    }
    throw AssertionError("Expected ManifestException")
  }
}
