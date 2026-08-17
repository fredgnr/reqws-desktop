package com.reqws.goland.manifest

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.charset.StandardCharsets

class ManifestParserTest {
  @Test
  fun `parses minimal and full schema v1 golden fixtures`() {
    val minimal = ManifestParser.parse(fixture("valid-minimal-v1.json"))
    val full = ManifestParser.parse(fixture("valid-full-v1.json"))

    assertEquals("ws_minimal", minimal.id)
    assertTrue(minimal.repositories.isEmpty())
    assertEquals(listOf("service-a", "service-b"), full.repositories.map { it.name })
  }

  @Test
  fun `ignores unknown additive fields`() {
    val manifest = ManifestParser.parse(fixture("valid-unknown-fields-v1.json"))

    assertEquals("ws_unknown_fields", manifest.id)
    assertEquals("forward-compatible", manifest.repositories.single().name)
  }

  @Test
  fun `rejects unsupported major versions with a stable code`() {
    val failure = expectFailure { ManifestParser.parse(fixture("unsupported-v2.json")) }

    assertEquals(ManifestErrorCode.UNSUPPORTED_MANIFEST_VERSION, failure.code)
    assertEquals("schemaVersion", failure.field)
  }

  @Test
  fun `rejects repository names that collide after NFC case folding`() {
    val failure = expectFailure { ManifestParser.parse(fixture("invalid-duplicate-name.json")) }

    assertEquals(ManifestErrorCode.REPOSITORY_DUPLICATE, failure.code)
    assertEquals("repositories[1].name", failure.field)
  }

  @Test
  fun `rejects duplicate repository IDs independently of names`() {
    val duplicateId = fixture("valid-full-v1.json").toString(StandardCharsets.UTF_8)
      .replace("repo_service_b", "repo_service_a")

    val failure = expectFailure { ManifestParser.parse(duplicateId.toByteArray()) }

    assertEquals(ManifestErrorCode.REPOSITORY_DUPLICATE, failure.code)
    assertEquals("repositories[1].catalogRepositoryId", failure.field)
  }

  @Test
  fun `rejects an unsafe relative path before touching the filesystem`() {
    val failure = expectFailure { ManifestParser.parse(fixture("invalid-relative-path.json")) }

    assertEquals(ManifestErrorCode.REPOSITORY_PATH_INVALID, failure.code)
    assertEquals("repositories[0].relativePath", failure.field)
  }

  @Test
  fun `rejects malformed JSON and a non-object root`() {
    assertEquals(
      ManifestErrorCode.MANIFEST_INVALID_JSON,
      expectFailure { ManifestParser.parse("{".toByteArray()) }.code,
    )
    assertEquals(
      ManifestErrorCode.MANIFEST_INVALID_JSON,
      expectFailure { ManifestParser.parse("[]".toByteArray()) }.code,
    )
  }

  @Test
  fun `rejects invalid UTF-8 without replacement characters`() {
    val invalidUtf8 = byteArrayOf('{'.code.toByte(), '"'.code.toByte(), 0xc3.toByte(), 0x28)

    assertEquals(
      ManifestErrorCode.MANIFEST_INVALID_ENCODING,
      expectFailure { ManifestParser.parse(invalidUtf8) }.code,
    )
  }

  @Test
  fun `rejects a UTF-8 BOM instead of silently changing the JSON document`() {
    val bom = byteArrayOf(0xef.toByte(), 0xbb.toByte(), 0xbf.toByte())
    val document = bom + fixture("valid-minimal-v1.json")

    assertEquals(
      ManifestErrorCode.MANIFEST_INVALID_JSON,
      expectFailure { ManifestParser.parse(document) }.code,
    )
  }

  @Test
  fun `enforces the one MiB input gate`() {
    val oversized = ByteArray(ManifestParser.MAX_MANIFEST_BYTES + 1) { ' '.code.toByte() }

    assertEquals(
      ManifestErrorCode.MANIFEST_TOO_LARGE,
      expectFailure { ManifestParser.parse(oversized) }.code,
    )
  }

  @Test
  fun `validates required types lengths paths and UTC timestamps`() {
    val valid = fixture("valid-minimal-v1.json").toString(StandardCharsets.UTF_8)
    val mutations = listOf(
      valid.replace("\"id\": \"ws_minimal\"", "\"id\": 7"),
      valid.replace("\"id\": \"ws_minimal\"", "\"id\": \"   \""),
      valid.replace("\"name\": \"Minimal workspace\"", "\"name\": \"${"x".repeat(256)}\""),
      valid.replace("\"rootPath\": \"/tmp/reqws-golden-workspace\"", "\"rootPath\": \"relative\""),
      valid.replace("2026-08-14T00:00:00.000Z", "2026-08-14T00:00:00+08:00"),
      valid.replace("2026-08-14T00:00:00.000Z", "2026-02-30T00:00:00.000Z"),
      valid.replace("2026-08-14T00:00:00.000Z", "2026-08-14T24:00:00.000Z"),
      valid.replace("2026-08-14T00:00:00.000Z", "2026-08-14T00:00:60.000Z"),
    )

    mutations.forEach { mutation ->
      assertEquals(
        ManifestErrorCode.MANIFEST_SCHEMA_INVALID,
        expectFailure { ManifestParser.parse(mutation.toByteArray()) }.code,
      )
    }
  }

  @Test
  fun `accepts the UTC timestamp precisions supported by the Desktop schema`() {
    val valid = fixture("valid-minimal-v1.json").toString(StandardCharsets.UTF_8)
    listOf(
      "2026-08-14T00:00Z",
      "2026-08-14T00:00:00Z",
      "2026-08-14T00:00:00.1Z",
      "2026-08-14T00:00:00.1234567890Z",
    ).forEach { timestamp ->
      val manifest = valid.replace("2026-08-14T00:00:00.000Z", timestamp)
      assertEquals(timestamp, ManifestParser.parse(manifest.toByteArray()).createdAt)
    }
  }

  @Test
  fun `trims strings like the Desktop schema`() {
    val valid = fixture("valid-full-v1.json").toString(StandardCharsets.UTF_8)
      .replace("\"id\": \"ws_full\"", "\"id\": \"  ws_full  \"")
      .replace("\"name\": \"service-a\"", "\"name\": \" service-a \"")
      .replace("\"relativePath\": \"service-a\"", "\"relativePath\": \" service-a \"")

    val manifest = ManifestParser.parse(valid.toByteArray())

    assertEquals("ws_full", manifest.id)
    assertEquals("service-a", manifest.repositories.first().name)
  }

  @Test
  fun `matches Desktop ECMA TrimString edge characters`() {
    val valid = ManifestParser.parse(fixture("valid-ecma-trim-v1.json"))

    assertEquals("ws_ecma_trim", valid.id)
    assertEquals("ECMA trim workspace", valid.name)
    assertEquals("feature/ecma-trim", valid.featureBranch)
    assertEquals("/tmp/reqws-golden-workspace", valid.rootPath)
    assertEquals("/tmp/reqws-files/ecma-trim.code-workspace", valid.workspaceFilePath)
    val repository = valid.repositories.single()
    assertEquals("repo_ecma_trim", repository.catalogRepositoryId)
    assertEquals("ecma-trim", repository.name)
    assertEquals("https://example.test/team/ecma-trim.git", repository.url)
    assertEquals("main", repository.defaultBranch)
    assertEquals("ecma-trim", repository.relativePath)

    val failure = expectFailure {
      ManifestParser.parse(fixture("invalid-non-ecma-trim-control-v1.json"))
    }
    assertEquals(ManifestErrorCode.MANIFEST_SCHEMA_INVALID, failure.code)
    assertEquals("rootPath", failure.field)
  }

  @Test
  fun `redacts repository URLs from model string representations`() {
    val manifest = ManifestParser.parse(fixture("valid-full-v1.json"))
    val rendered = manifest.repositories.first().toString()

    assertFalse(rendered.contains("example.test"))
    assertFalse(manifest.toString().contains("example.test"))
  }

  private fun fixture(name: String): ByteArray =
    checkNotNull(javaClass.getResourceAsStream("/manifests/$name")) { "Missing fixture: $name" }
      .use { it.readBytes() }

  private fun expectFailure(block: () -> Unit): ManifestException {
    try {
      block()
    } catch (exception: ManifestException) {
      return exception
    }
    throw AssertionError("Expected ManifestException")
  }
}
