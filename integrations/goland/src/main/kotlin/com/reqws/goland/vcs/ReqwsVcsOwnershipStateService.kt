package com.reqws.goland.vcs

import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.StoragePathMacros
import com.reqws.goland.persistence.AtomicStateCodec
import com.reqws.goland.persistence.VerifiedAtomicStateFile
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.nio.file.Path
import java.security.MessageDigest

/**
 * VCS ownership is stored outside the IntelliJ component store so a destructive mapping update
 * can be preceded by an independently forced, atomically verified revocation journal.
 */
@Service(Service.Level.PROJECT)
@State(
  name = "ReqwsVcsOwnershipState",
  storages = [Storage(StoragePathMacros.WORKSPACE_FILE)],
)
internal class ReqwsVcsOwnershipStateService :
  PersistentStateComponent<ReqwsVcsOwnershipStateService.LegacyPersistedState> {
  private var legacyState = LegacyPersistedState()

  @Synchronized
  override fun getState(): LegacyPersistedState = legacyState.deepCopy()

  @Synchronized
  override fun loadState(state: LegacyPersistedState) {
    legacyState = state.deepCopy()
  }

  fun readForProject(projectRoot: Path): VcsOwnershipLoadResult {
    val persisted = try {
      readOrMigrate(projectRoot)
    } catch (_: Exception) {
      return conflictLoadResult()
    } ?: return VcsOwnershipLoadResult(
      ownership = emptyList(),
      pendingAdds = emptyList(),
      pendingRemovals = emptyList(),
      diagnostics = emptyList(),
    )

    val resolvedIdentities = hashSetOf<String>()
    fun resolve(relativeDirectory: String): OwnedPath? {
      val resolved = VcsPathIdentity.resolveOwned(projectRoot, relativeDirectory) ?: return null
      return resolved.takeIf { resolvedIdentities.add(it.lexicalIdentity) }
    }

    val ownership = ArrayList<VcsMappingOwnership>()
    val pendingAdds = ArrayList<VcsMappingPendingOwnership>()
    val pendingRemovals = ArrayList<VcsMappingPendingOwnership>()
    persisted.stableMappings.forEach { entry ->
      val resolved = resolve(entry.relativeDirectory) ?: return conflictLoadResult()
      val kind = try {
        VcsMappingOwnershipKind.valueOf(entry.kind)
      } catch (_: IllegalArgumentException) {
        return conflictLoadResult()
      }
      ownership.add(VcsMappingOwnership(resolved.relativeDirectory, kind))
    }
    persisted.pendingAdds.forEach { entry ->
      val resolved = resolve(entry.relativeDirectory) ?: return conflictLoadResult()
      pendingAdds.add(VcsMappingPendingOwnership(resolved.relativeDirectory, entry.operationToken))
    }
    persisted.pendingRemovals.forEach { entry ->
      val resolved = resolve(entry.relativeDirectory) ?: return conflictLoadResult()
      pendingRemovals.add(VcsMappingPendingOwnership(resolved.relativeDirectory, entry.operationToken))
    }
    return VcsOwnershipLoadResult(
      ownership = ownership,
      pendingAdds = pendingAdds,
      pendingRemovals = pendingRemovals,
      diagnostics = emptyList(),
    )
  }

  /** Resolves all paths before an EDT mapping checkpoint and prepares immutable file contents. */
  fun prepareReplacementForProject(
    projectRoot: Path,
    state: VcsMappingOwnershipState,
  ): PreparedReplacement {
    val identities = hashSetOf<String>()
    val tokens = hashSetOf<String>()
    fun resolve(relativeDirectory: String): OwnedPath {
      val resolved = requireNotNull(VcsPathIdentity.resolveOwned(projectRoot, relativeDirectory)) {
        "VCS ownership must use a canonical workspace-relative path"
      }
      require(identities.add(resolved.lexicalIdentity)) {
        "VCS ownership phases must not contain duplicate paths"
      }
      return resolved
    }
    fun pending(entry: VcsMappingPendingOwnership): PersistedPendingMapping {
      val resolved = resolve(entry.relativeDirectory)
      require(OPERATION_TOKEN.matches(entry.operationToken)) {
        "VCS ownership transition tokens must be 128-bit lowercase hexadecimal values"
      }
      require(tokens.add(entry.operationToken)) {
        "VCS ownership transition tokens must be unique"
      }
      return PersistedPendingMapping(resolved.relativeDirectory, entry.operationToken)
    }

    val stable = state.stableMappings.map { item ->
      val resolved = resolve(item.relativeDirectory)
      PersistedMapping(resolved.relativeDirectory, item.kind.name)
    }
    val file = stateFile(projectRoot)
    val expected = readOrMigrate(projectRoot)
    val replacement = PersistedState(
      stateVersion = CURRENT_STATE_VERSION,
      generation = (expected?.generation ?: 0L) + 1L,
      workspaceRootFingerprint = rootFingerprint(projectRoot),
      stableMappings = stable.sortedBy(PersistedMapping::relativeDirectory),
      pendingAdds = state.pendingAdds.map(::pending)
        .sortedBy(PersistedPendingMapping::relativeDirectory),
      pendingRemovals = state.pendingRemovals.map(::pending)
        .sortedBy(PersistedPendingMapping::relativeDirectory),
    )
    validatePersistedState(replacement)
    return PreparedReplacement(file, expected, replacement)
  }

  /** Performs fsync + atomic replace + strict decode/read-back through the shared file primitive. */
  @Synchronized
  fun persistPreparedReplacement(replacement: PreparedReplacement) {
    val current = replacement.file.read()
    require(current == replacement.expectedState) {
      "ReqWS VCS ownership generation changed before the prepared write"
    }
    replacement.file.writeAndVerify(replacement.state)
  }

  internal class PreparedReplacement internal constructor(
    internal val file: VerifiedAtomicStateFile<PersistedState>,
    internal val expectedState: PersistedState?,
    internal val state: PersistedState,
  )

  internal data class PersistedState(
    val stateVersion: Int,
    val generation: Long,
    val workspaceRootFingerprint: String,
    val stableMappings: List<PersistedMapping>,
    val pendingAdds: List<PersistedPendingMapping>,
    val pendingRemovals: List<PersistedPendingMapping>,
  )

  internal data class PersistedMapping(
    val relativeDirectory: String,
    val kind: String,
  )

  internal data class PersistedPendingMapping(
    val relativeDirectory: String,
    val operationToken: String,
  )

  internal data class LegacyPersistedState(
    var stateVersion: Int = LEGACY_STATE_VERSION,
    var managedMappings: MutableList<LegacyPersistedMapping> = mutableListOf(),
  ) {
    fun deepCopy(): LegacyPersistedState = copy(
      managedMappings = managedMappings.map { it.copy() }.toMutableList(),
    )
  }

  internal data class LegacyPersistedMapping(
    var relativeDirectory: String = "",
    var kind: String = "",
  )

  @Synchronized
  private fun readOrMigrate(projectRoot: Path): PersistedState? {
    val file = stateFile(projectRoot)
    val persisted = file.read()
    if (persisted != null) {
      require(persisted.workspaceRootFingerprint == rootFingerprint(projectRoot)) {
        "ReqWS VCS ownership belongs to a different workspace root"
      }
      return persisted
    }
    if (legacyState.managedMappings.isEmpty()) return null
    require(legacyState.stateVersion == LEGACY_STATE_VERSION) {
      "Unsupported legacy ReqWS VCS ownership state"
    }
    val migrated = PersistedState(
      stateVersion = CURRENT_STATE_VERSION,
      generation = 1L,
      workspaceRootFingerprint = rootFingerprint(projectRoot),
      stableMappings = legacyState.managedMappings.map { entry ->
        val resolved = requireNotNull(
          VcsPathIdentity.resolveOwned(projectRoot, entry.relativeDirectory),
        ) { "Invalid legacy ReqWS VCS ownership path" }
        require(entry.kind == "CREATED" || entry.kind == "BORROWED") {
          "Invalid legacy ReqWS VCS ownership kind"
        }
        PersistedMapping(resolved.relativeDirectory, entry.kind)
      }.sortedBy(PersistedMapping::relativeDirectory),
      pendingAdds = emptyList(),
      pendingRemovals = emptyList(),
    )
    validatePersistedState(migrated)
    file.writeAndVerify(migrated)
    legacyState = LegacyPersistedState()
    return migrated
  }

  private fun stateFile(projectRoot: Path): VerifiedAtomicStateFile<PersistedState> =
    VerifiedAtomicStateFile(
      file = projectRoot.resolve(STATE_DIRECTORY).resolve(STATE_FILE_NAME),
      maxBytes = MAX_STATE_BYTES,
      codec = VcsOwnershipStateCodec,
      validate = ::validatePersistedState,
    )

  private fun conflictLoadResult() = VcsOwnershipLoadResult(
    ownership = emptyList(),
    pendingAdds = emptyList(),
    pendingRemovals = emptyList(),
    diagnostics = listOf(VcsMappingDiagnostic(VcsMappingDiagnosticCode.OWNERSHIP_CONFLICT)),
  )

  companion object {
    const val CURRENT_STATE_VERSION = 2
    const val STATE_FILE_NAME = "reqws-vcs-ownership.json"
    private const val STATE_DIRECTORY = ".idea"
    private const val MAX_STATE_BYTES = 256 * 1024
    private val OPERATION_TOKEN = Regex("[0-9a-f]{32}")
    private val ROOT_FINGERPRINT = Regex("[0-9a-f]{64}")

    private fun validatePersistedState(state: PersistedState) {
      require(state.stateVersion == CURRENT_STATE_VERSION) {
        "Unsupported ReqWS VCS ownership state version"
      }
      require(state.generation > 0L) { "Invalid ReqWS VCS ownership generation" }
      require(ROOT_FINGERPRINT.matches(state.workspaceRootFingerprint)) {
        "Invalid ReqWS VCS workspace root fingerprint"
      }
      val entryCount = state.stableMappings.size +
        state.pendingAdds.size + state.pendingRemovals.size
      require(entryCount <= MAX_OWNERSHIP_ENTRIES) { "Too many ReqWS VCS ownership entries" }
      state.stableMappings.forEach { entry ->
        require(entry.relativeDirectory.isNotBlank()) { "Blank VCS ownership path" }
        require(entry.kind == "CREATED" || entry.kind == "BORROWED") {
          "Unsupported VCS ownership kind"
        }
      }
      val tokens = hashSetOf<String>()
      (state.pendingAdds + state.pendingRemovals).forEach { entry ->
        require(entry.relativeDirectory.isNotBlank()) { "Blank pending VCS ownership path" }
        require(OPERATION_TOKEN.matches(entry.operationToken)) {
          "Invalid pending VCS ownership token"
        }
        require(tokens.add(entry.operationToken)) { "Duplicate pending VCS ownership token" }
      }
    }

    private const val MAX_OWNERSHIP_ENTRIES = 4096
    private const val LEGACY_STATE_VERSION = 1

    private fun rootFingerprint(projectRoot: Path): String {
      val digest = MessageDigest.getInstance("SHA-256")
        .digest(VcsPathIdentity.lexical(projectRoot).toByteArray(StandardCharsets.UTF_8))
      return digest.joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
    }
  }
}

internal data class VcsOwnershipLoadResult(
  /** Only stable entries are deletion evidence; pending phases are non-authorizing tombstones. */
  val ownership: List<VcsMappingOwnership>,
  val pendingAdds: List<VcsMappingPendingOwnership>,
  val pendingRemovals: List<VcsMappingPendingOwnership>,
  val diagnostics: List<VcsMappingDiagnostic>,
)

private object VcsOwnershipStateCodec : AtomicStateCodec<ReqwsVcsOwnershipStateService.PersistedState> {
  override fun encode(value: ReqwsVcsOwnershipStateService.PersistedState): ByteArray = buildString {
    append("{\"stateVersion\":")
    append(value.stateVersion)
    append(",\"generation\":")
    append(value.generation)
    append(",\"workspaceRootFingerprint\":")
    appendJsonString(value.workspaceRootFingerprint)
    append(",\"stableMappings\":")
    appendStable(value.stableMappings)
    append(",\"pendingAdds\":")
    appendPending(value.pendingAdds)
    append(",\"pendingRemovals\":")
    appendPending(value.pendingRemovals)
    append('}')
  }.toByteArray(StandardCharsets.UTF_8)

  override fun decode(bytes: ByteArray): ReqwsVcsOwnershipStateService.PersistedState {
    val decoder = StandardCharsets.UTF_8.newDecoder()
      .onMalformedInput(CodingErrorAction.REPORT)
      .onUnmappableCharacter(CodingErrorAction.REPORT)
    val cursor = JsonCursor(decoder.decode(ByteBuffer.wrap(bytes)).toString())
    cursor.expect('{')
    cursor.expectKey("stateVersion")
    val version = cursor.readPositiveInt()
    cursor.expect(',')
    cursor.expectKey("generation")
    val generation = cursor.readPositiveLong()
    cursor.expect(',')
    cursor.expectKey("workspaceRootFingerprint")
    val workspaceRootFingerprint = cursor.readString()
    cursor.expect(',')
    cursor.expectKey("stableMappings")
    val stable = cursor.readArray {
      cursor.expect('{')
      cursor.expectKey("relativeDirectory")
      val relativeDirectory = cursor.readString()
      cursor.expect(',')
      cursor.expectKey("kind")
      val kind = cursor.readString()
      cursor.expect('}')
      ReqwsVcsOwnershipStateService.PersistedMapping(relativeDirectory, kind)
    }
    cursor.expect(',')
    cursor.expectKey("pendingAdds")
    val pendingAdds = cursor.readPendingArray()
    cursor.expect(',')
    cursor.expectKey("pendingRemovals")
    val pendingRemovals = cursor.readPendingArray()
    cursor.expect('}')
    cursor.requireEnd()
    return ReqwsVcsOwnershipStateService.PersistedState(
      stateVersion = version,
      generation = generation,
      workspaceRootFingerprint = workspaceRootFingerprint,
      stableMappings = stable,
      pendingAdds = pendingAdds,
      pendingRemovals = pendingRemovals,
    )
  }

  private fun StringBuilder.appendStable(
    entries: List<ReqwsVcsOwnershipStateService.PersistedMapping>,
  ) {
    append('[')
    entries.forEachIndexed { index, entry ->
      if (index > 0) append(',')
      append("{\"relativeDirectory\":")
      appendJsonString(entry.relativeDirectory)
      append(",\"kind\":")
      appendJsonString(entry.kind)
      append('}')
    }
    append(']')
  }

  private fun StringBuilder.appendPending(
    entries: List<ReqwsVcsOwnershipStateService.PersistedPendingMapping>,
  ) {
    append('[')
    entries.forEachIndexed { index, entry ->
      if (index > 0) append(',')
      append("{\"relativeDirectory\":")
      appendJsonString(entry.relativeDirectory)
      append(",\"operationToken\":")
      appendJsonString(entry.operationToken)
      append('}')
    }
    append(']')
  }

  private fun StringBuilder.appendJsonString(value: String) {
    append('"')
    value.forEach { character ->
      when (character) {
        '"' -> append("\\\"")
        '\\' -> append("\\\\")
        '\b' -> append("\\b")
        '\u000c' -> append("\\f")
        '\n' -> append("\\n")
        '\r' -> append("\\r")
        '\t' -> append("\\t")
        else -> if (character.code < 0x20) {
          append("\\u")
          append(character.code.toString(16).padStart(4, '0'))
        } else {
          append(character)
        }
      }
    }
    append('"')
  }

  private fun JsonCursor.readPendingArray(): List<ReqwsVcsOwnershipStateService.PersistedPendingMapping> =
    readArray {
      expect('{')
      expectKey("relativeDirectory")
      val relativeDirectory = readString()
      expect(',')
      expectKey("operationToken")
      val operationToken = readString()
      expect('}')
      ReqwsVcsOwnershipStateService.PersistedPendingMapping(relativeDirectory, operationToken)
    }
}

private class JsonCursor(private val source: String) {
  private var offset = 0

  fun expect(character: Char) {
    skipWhitespace()
    require(offset < source.length && source[offset] == character) {
      "Malformed ReqWS VCS ownership JSON"
    }
    offset += 1
  }

  fun expectKey(key: String) {
    require(readString() == key) { "Unexpected ReqWS VCS ownership JSON key" }
    expect(':')
  }

  fun readPositiveInt(): Int {
    val value = readPositiveLong()
    require(value <= Int.MAX_VALUE) { "ReqWS VCS ownership integer is too large" }
    return value.toInt()
  }

  fun readPositiveLong(): Long {
    skipWhitespace()
    val start = offset
    while (offset < source.length && source[offset].isDigit()) offset += 1
    require(offset > start) { "Expected ReqWS VCS ownership state version" }
    return source.substring(start, offset).toLong()
  }

  fun readString(): String {
    skipWhitespace()
    require(offset < source.length && source[offset] == '"') {
      "Expected ReqWS VCS ownership JSON string"
    }
    offset += 1
    val result = StringBuilder()
    while (offset < source.length) {
      val character = source[offset++]
      when {
        character == '"' -> return result.toString()
        character == '\\' -> result.append(readEscape())
        character.code < 0x20 -> error("Unescaped control character in ownership JSON")
        else -> result.append(character)
      }
    }
    error("Unterminated ReqWS VCS ownership JSON string")
  }

  fun <T> readArray(readElement: () -> T): List<T> {
    expect('[')
    skipWhitespace()
    if (offset < source.length && source[offset] == ']') {
      offset += 1
      return emptyList()
    }
    val result = ArrayList<T>()
    while (true) {
      result.add(readElement())
      skipWhitespace()
      if (offset < source.length && source[offset] == ']') {
        offset += 1
        return result
      }
      expect(',')
    }
  }

  fun requireEnd() {
    skipWhitespace()
    require(offset == source.length) { "Trailing ReqWS VCS ownership JSON content" }
  }

  private fun readEscape(): Char {
    require(offset < source.length) { "Incomplete ReqWS VCS ownership JSON escape" }
    return when (val escaped = source[offset++]) {
      '"', '\\', '/' -> escaped
      'b' -> '\b'
      'f' -> '\u000c'
      'n' -> '\n'
      'r' -> '\r'
      't' -> '\t'
      'u' -> {
        require(offset + 4 <= source.length) { "Incomplete ownership JSON unicode escape" }
        val value = source.substring(offset, offset + 4).toInt(16)
        offset += 4
        value.toChar()
      }
      else -> error("Unsupported ReqWS VCS ownership JSON escape")
    }
  }

  private fun skipWhitespace() {
    while (offset < source.length && source[offset] in setOf(' ', '\t', '\r', '\n')) offset += 1
  }
}
