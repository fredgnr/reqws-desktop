package com.reqws.goland.manifest

import java.math.BigDecimal
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.nio.file.InvalidPathException
import java.nio.file.Path
import java.text.Normalizer
import java.time.DateTimeException
import java.time.LocalDate
import java.util.Locale

object ManifestParser {
  const val MAX_MANIFEST_BYTES: Int = 1024 * 1024

  private const val MAX_ID_LENGTH = 200
  private const val MAX_NAME_LENGTH = 255
  private const val MAX_BRANCH_LENGTH = 1024
  private const val MAX_PATH_LENGTH = 16_384
  private const val MAX_URL_LENGTH = 8_192
  private val isoInstant = Regex(
    "^(\\d{4})-(\\d{2})-(\\d{2})T(\\d{2}):(\\d{2})(?::(\\d{2})(?:\\.(\\d+))?)?Z$",
  )

  fun parse(bytes: ByteArray): WorkspaceManifest {
    if (bytes.size > MAX_MANIFEST_BYTES) {
      throw ManifestException(ManifestErrorCode.MANIFEST_TOO_LARGE)
    }
    val text = decodeUtf8(bytes)
    val root = try {
      JsonParser(text).parseObjectDocument()
    } catch (_: JsonSyntaxException) {
      throw ManifestException(ManifestErrorCode.MANIFEST_INVALID_JSON)
    }
    return parseManifest(root)
  }

  private fun decodeUtf8(bytes: ByteArray): String {
    val decoder = StandardCharsets.UTF_8.newDecoder()
      .onMalformedInput(CodingErrorAction.REPORT)
      .onUnmappableCharacter(CodingErrorAction.REPORT)
    return try {
      decoder.decode(ByteBuffer.wrap(bytes)).toString()
    } catch (_: Exception) {
      throw ManifestException(ManifestErrorCode.MANIFEST_INVALID_ENCODING)
    }
  }

  private fun parseManifest(root: Map<String, JsonValue>): WorkspaceManifest {
    val versionValue = root["schemaVersion"]
      ?: schemaFailure("schemaVersion")
    val versionNumber = (versionValue as? JsonNumber)?.value
      ?: schemaFailure("schemaVersion")
    if (versionNumber.compareTo(BigDecimal.ONE) != 0) {
      throw ManifestException(
        ManifestErrorCode.UNSUPPORTED_MANIFEST_VERSION,
        field = "schemaVersion",
      )
    }

    val repositoriesValue = root["repositories"]
      ?: schemaFailure("repositories")
    val repositoryArray = (repositoriesValue as? JsonArray)?.values
      ?: schemaFailure("repositories")
    val repositories = repositoryArray.mapIndexed { index, value ->
      val repository = (value as? JsonObject)?.values
        ?: schemaFailure("repositories[$index]")
      parseRepository(repository, index)
    }

    validateRepositoryUniqueness(repositories)
    return WorkspaceManifest(
      schemaVersion = 1,
      id = root.requiredString("id", MAX_ID_LENGTH),
      name = root.requiredString("name", MAX_NAME_LENGTH),
      featureBranch = root.requiredString("featureBranch", MAX_BRANCH_LENGTH),
      rootPath = root.requiredAbsolutePath("rootPath"),
      workspaceFilePath = root.requiredAbsolutePath("workspaceFilePath"),
      repositories = repositories,
      createdAt = root.requiredIsoInstant("createdAt"),
      updatedAt = root.requiredIsoInstant("updatedAt"),
    )
  }

  private fun parseRepository(
    value: Map<String, JsonValue>,
    index: Int,
  ): WorkspaceRepository {
    val prefix = "repositories[$index]"
    val name = value.requiredRepositoryName("name", "$prefix.name")
    val relativePath = value.requiredRepositoryName("relativePath", "$prefix.relativePath")
    if (relativePath != name) {
      throw ManifestException(
        ManifestErrorCode.REPOSITORY_PATH_INVALID,
        field = "$prefix.relativePath",
      )
    }
    val url = value.requiredString("url", MAX_URL_LENGTH, "$prefix.url")
    if (!RepositoryUrlSafety.isSafe(url)) {
      schemaFailure("$prefix.url")
    }
    return WorkspaceRepository(
      catalogRepositoryId = value.requiredString(
        "catalogRepositoryId",
        MAX_ID_LENGTH,
        "$prefix.catalogRepositoryId",
      ),
      name = name,
      url = url,
      defaultBranch = value.requiredString(
        "defaultBranch",
        MAX_BRANCH_LENGTH,
        "$prefix.defaultBranch",
      ),
      relativePath = relativePath,
    )
  }

  private fun validateRepositoryUniqueness(repositories: List<WorkspaceRepository>) {
    val ids = HashSet<String>()
    val names = HashSet<String>()
    repositories.forEachIndexed { index, repository ->
      if (!ids.add(repository.catalogRepositoryId)) {
        throw ManifestException(
          ManifestErrorCode.REPOSITORY_DUPLICATE,
          field = "repositories[$index].catalogRepositoryId",
        )
      }
      val nameKey = Normalizer.normalize(repository.name, Normalizer.Form.NFC)
        .trimEcmaWhitespace()
        .lowercase(Locale.US)
      if (!names.add(nameKey)) {
        throw ManifestException(
          ManifestErrorCode.REPOSITORY_DUPLICATE,
          field = "repositories[$index].name",
        )
      }
    }
  }

  private fun Map<String, JsonValue>.requiredString(
    key: String,
    maxLength: Int,
    field: String = key,
  ): String {
    val raw = (this[key] as? JsonString)?.value ?: schemaFailure(field)
    val value = raw.trimEcmaWhitespace()
    if (value.isEmpty() || value.length > maxLength) schemaFailure(field)
    return value
  }

  private fun Map<String, JsonValue>.requiredRepositoryName(
    key: String,
    field: String,
  ): String {
    val value = requiredString(key, MAX_NAME_LENGTH, field)
    if (value == "." || value == ".." || value.contains('/') || value.contains('\\')) {
      throw ManifestException(ManifestErrorCode.REPOSITORY_PATH_INVALID, field = field)
    }
    try {
      if (Path.of(value).isAbsolute || Path.of(value).any { it.toString() == ".." }) {
        throw ManifestException(ManifestErrorCode.REPOSITORY_PATH_INVALID, field = field)
      }
    } catch (_: InvalidPathException) {
      throw ManifestException(ManifestErrorCode.REPOSITORY_PATH_INVALID, field = field)
    }
    return value
  }

  private fun Map<String, JsonValue>.requiredAbsolutePath(key: String): String {
    val value = requiredString(key, MAX_PATH_LENGTH)
    if (!value.startsWith('/')) schemaFailure(key)
    try {
      if (!Path.of(value).isAbsolute) schemaFailure(key)
    } catch (_: InvalidPathException) {
      schemaFailure(key)
    }
    return value
  }

  private fun Map<String, JsonValue>.requiredIsoInstant(key: String): String {
    val value = (this[key] as? JsonString)?.value ?: schemaFailure(key)
    val match = isoInstant.matchEntire(value) ?: schemaFailure(key)
    val (year, month, day, hour, minute, second) = match.destructured
    try {
      LocalDate.of(year.toInt(), month.toInt(), day.toInt())
      if (hour.toInt() !in 0..23 || minute.toInt() !in 0..59) schemaFailure(key)
      if (second.isNotEmpty() && second.toInt() !in 0..59) schemaFailure(key)
    } catch (_: DateTimeException) {
      schemaFailure(key)
    }
    return value
  }

  private fun schemaFailure(field: String): Nothing =
    throw ManifestException(ManifestErrorCode.MANIFEST_SCHEMA_INVALID, field = field)
}

/** Matches ECMAScript TrimString, which backs Zod's `z.string().trim()`. */
private fun String.trimEcmaWhitespace(): String {
  var start = 0
  var end = length
  while (start < end && this[start].isEcmaTrimCharacter()) start += 1
  while (end > start && this[end - 1].isEcmaTrimCharacter()) end -= 1
  return if (start == 0 && end == length) this else substring(start, end)
}

private fun Char.isEcmaTrimCharacter(): Boolean = when (this) {
  '\u0009',
  '\u000A',
  '\u000B',
  '\u000C',
  '\u000D',
  '\u0020',
  '\u00A0',
  '\u1680',
  in '\u2000'..'\u200A',
  '\u2028',
  '\u2029',
  '\u202F',
  '\u205F',
  '\u3000',
  '\uFEFF',
  -> true
  else -> false
}

private sealed interface JsonValue

private data class JsonObject(val values: Map<String, JsonValue>) : JsonValue

private data class JsonArray(val values: List<JsonValue>) : JsonValue

private data class JsonString(val value: String) : JsonValue

private data class JsonNumber(val value: BigDecimal) : JsonValue

private data class JsonBoolean(val value: Boolean) : JsonValue

private data object JsonNull : JsonValue

private class JsonSyntaxException : Exception()

/** A deliberately small, dependency-free RFC 8259 parser for the manifest contract. */
private class JsonParser(private val input: String) {
  private var offset = 0

  fun parseObjectDocument(): Map<String, JsonValue> {
    skipWhitespace()
    val value = parseValue(depth = 0)
    skipWhitespace()
    if (offset != input.length) fail()
    return (value as? JsonObject)?.values ?: fail()
  }

  private fun parseValue(depth: Int): JsonValue {
    if (depth > MAX_DEPTH || offset >= input.length) fail()
    return when (input[offset]) {
      '{' -> parseObject(depth + 1)
      '[' -> parseArray(depth + 1)
      '"' -> JsonString(parseString())
      't' -> parseLiteral("true", JsonBoolean(true))
      'f' -> parseLiteral("false", JsonBoolean(false))
      'n' -> parseLiteral("null", JsonNull)
      '-', in '0'..'9' -> parseNumber()
      else -> fail()
    }
  }

  private fun parseObject(depth: Int): JsonObject {
    expect('{')
    skipWhitespace()
    val values = LinkedHashMap<String, JsonValue>()
    if (consume('}')) return JsonObject(values)
    while (true) {
      if (offset >= input.length || input[offset] != '"') fail()
      val key = parseString()
      skipWhitespace()
      expect(':')
      skipWhitespace()
      // JSON.parse and Zod observe the last value for duplicate object keys.
      values[key] = parseValue(depth)
      skipWhitespace()
      if (consume('}')) return JsonObject(values)
      expect(',')
      skipWhitespace()
    }
  }

  private fun parseArray(depth: Int): JsonArray {
    expect('[')
    skipWhitespace()
    val values = ArrayList<JsonValue>()
    if (consume(']')) return JsonArray(values)
    while (true) {
      values.add(parseValue(depth))
      skipWhitespace()
      if (consume(']')) return JsonArray(values)
      expect(',')
      skipWhitespace()
    }
  }

  private fun parseString(): String {
    expect('"')
    val result = StringBuilder()
    while (offset < input.length) {
      val character = input[offset++]
      when {
        character == '"' -> return result.toString()
        character == '\\' -> result.append(parseEscape())
        character.code < 0x20 -> fail()
        else -> result.append(character)
      }
    }
    fail()
  }

  private fun parseEscape(): Char {
    if (offset >= input.length) fail()
    return when (val escaped = input[offset++]) {
      '"', '\\', '/' -> escaped
      'b' -> '\b'
      'f' -> '\u000c'
      'n' -> '\n'
      'r' -> '\r'
      't' -> '\t'
      'u' -> parseUnicodeEscape()
      else -> fail()
    }
  }

  private fun parseUnicodeEscape(): Char {
    if (offset + 4 > input.length) fail()
    var value = 0
    repeat(4) {
      val digit = input[offset++].digitToIntOrNull(16) ?: fail()
      value = value * 16 + digit
    }
    return value.toChar()
  }

  private fun parseNumber(): JsonNumber {
    val start = offset
    consume('-')
    if (consume('0')) {
      if (offset < input.length && input[offset].isDigit()) fail()
    } else {
      requireDigits()
    }
    if (consume('.')) requireDigits()
    if (offset < input.length && (input[offset] == 'e' || input[offset] == 'E')) {
      offset++
      if (offset < input.length && (input[offset] == '+' || input[offset] == '-')) offset++
      requireDigits()
    }
    val value = try {
      BigDecimal(input.substring(start, offset))
    } catch (_: NumberFormatException) {
      fail()
    }
    return JsonNumber(value)
  }

  private fun requireDigits() {
    val start = offset
    while (offset < input.length && input[offset].isDigit()) offset++
    if (start == offset) fail()
  }

  private fun <T : JsonValue> parseLiteral(literal: String, value: T): T {
    if (!input.startsWith(literal, offset)) fail()
    offset += literal.length
    return value
  }

  private fun skipWhitespace() {
    while (offset < input.length && input[offset] in JSON_WHITESPACE) offset++
  }

  private fun expect(character: Char) {
    if (!consume(character)) fail()
  }

  private fun consume(character: Char): Boolean {
    if (offset >= input.length || input[offset] != character) return false
    offset++
    return true
  }

  private fun fail(): Nothing = throw JsonSyntaxException()

  companion object {
    private const val MAX_DEPTH = 128
    private val JSON_WHITESPACE = charArrayOf(' ', '\t', '\n', '\r')
  }
}
