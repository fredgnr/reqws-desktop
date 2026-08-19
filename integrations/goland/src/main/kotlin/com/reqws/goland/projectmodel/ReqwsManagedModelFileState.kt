package com.reqws.goland.projectmodel

import com.reqws.goland.persistence.AtomicFileOperations
import com.reqws.goland.persistence.AtomicStateCodec
import com.reqws.goland.persistence.NioAtomicFileOperations
import com.reqws.goland.persistence.VerifiedAtomicStateFile
import com.reqws.goland.persistence.VerifiedAtomicStateFileException
import java.lang.management.ManagementFactory
import java.nio.ByteBuffer
import java.nio.channels.OverlappingFileLockException
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest

internal const val REQWS_MODEL_FILE_FORMAT_VERSION = 1
internal const val REQWS_MODEL_STATE_FILE_NAME = "reqws-managed-project-model.json"
private const val REQWS_MODEL_STATE_MAX_BYTES = 256 * 1024
private const val REQWS_MODEL_STATE_MAX_CLAIMS = 4096
private const val REQWS_MODEL_STATE_MAX_STRING = 1024
private val SHA256_PATTERN = Regex("^[0-9a-f]{64}$")
private val FILE_MARKER_TOKEN_PATTERN = Regex("^[0-9a-f]{32}$")
private val PROCESS_JVM_EPOCH: String by lazy {
  val runtime = ManagementFactory.getRuntimeMXBean()
  sha256Hex("${runtime.name}:${runtime.startTime}")
}

internal data class ManagedModelStateBinding(
  val workspaceId: String,
  val rootFingerprint: String,
)

internal data class DurableManagedClaim(
  val relativePath: String,
  val markerToken: String,
)

internal data class DurableManagedModelState(
  val formatVersion: Int = REQWS_MODEL_FILE_FORMAT_VERSION,
  val strategy: String = REQWS_MODEL_STRATEGY,
  val workspaceId: String,
  val rootFingerprint: String,
  val generation: Long,
  val writerJvmEpoch: String,
  val targetModuleName: String,
  val managedClaims: List<DurableManagedClaim>,
  val recoveryClaims: List<DurableManagedClaim>,
)

internal interface ManagedModelStateRepository {
  fun read(binding: ManagedModelStateBinding): DurableManagedModelState?

  fun write(
    binding: ManagedModelStateBinding,
    expectedGeneration: Long?,
    nextState: DurableManagedModelState,
  ): DurableManagedModelState
}

internal class VerifiedManagedModelStateRepository @JvmOverloads constructor(
  workspaceRoot: Path,
  operations: AtomicFileOperations = NioAtomicFileOperations,
) : ManagedModelStateRepository {
  private val canonicalWorkspaceRoot = workspaceRoot.toAbsolutePath().normalize()
  private val ideaDirectory = canonicalWorkspaceRoot.resolve(".idea")
  private val lockFileName = ideaDirectory.fileSystem.getPath(".$REQWS_MODEL_STATE_FILE_NAME.lock")
  private val stateFile = VerifiedAtomicStateFile(
    file = ideaDirectory.resolve(REQWS_MODEL_STATE_FILE_NAME),
    maxBytes = REQWS_MODEL_STATE_MAX_BYTES,
    codec = DurableManagedModelStateCodec,
    validate = ::validateDurableStateShape,
    operations = operations,
  )

  override fun read(binding: ManagedModelStateBinding): DurableManagedModelState? {
    ensureSafeStateLocation()
    return try {
      stateFile.read()?.also { state -> validateDurableState(state, binding) }
    } catch (exception: ProjectModelApplyException) {
      throw exception
    } catch (exception: Exception) {
      throw stateIoFailure("Unable to read the ReqWS managed-model state.", exception)
    }
  }

  override fun write(
    binding: ManagedModelStateBinding,
    expectedGeneration: Long?,
    nextState: DurableManagedModelState,
  ): DurableManagedModelState {
    ensureSafeStateLocation()
    return try {
      stateFile.withStableParent { stable ->
        stable.requireAbsentOrRegularFile(lockFileName)
        stable.openLockFile(lockFileName).use { channel ->
          val lock = try {
            channel.tryLock()
          } catch (exception: OverlappingFileLockException) {
            null
          } ?: throw stateIoFailure("Another ReqWS managed-model writer is active.")
          lock.use {
            val current = stable.read()
            current?.let { validateDurableState(it, binding) }
            if (current?.generation != expectedGeneration) {
              throw stateIoFailure("The ReqWS managed-model state changed concurrently.")
            }
            if (expectedGeneration == Long.MAX_VALUE) {
              throw stateIoFailure("The ReqWS managed-model state generation is exhausted.")
            }
            val persisted = nextState.copy(generation = (expectedGeneration ?: -1L) + 1L)
            validateDurableState(persisted, binding)
            stable.writeAndVerify(persisted)
            persisted
          }
        }
      }
    } catch (exception: Exception) {
      if (exception is ProjectModelApplyException) throw exception
      throw stateIoFailure("Unable to atomically persist the ReqWS managed-model state.", exception)
    }
  }

  private fun ensureSafeStateLocation() {
    val rootAttributes = try {
      Files.readAttributes(
        canonicalWorkspaceRoot,
        BasicFileAttributes::class.java,
        LinkOption.NOFOLLOW_LINKS,
      )
    } catch (exception: Exception) {
      throw stateIoFailure("Unable to verify the ReqWS workspace root.", exception)
    }
    if (!rootAttributes.isDirectory || rootAttributes.isSymbolicLink) {
      throw stateIoFailure("The ReqWS workspace root must be a real directory.")
    }
    val canonicalRoot = try {
      canonicalWorkspaceRoot.toRealPath(LinkOption.NOFOLLOW_LINKS)
    } catch (exception: Exception) {
      throw stateIoFailure("Unable to canonicalize the ReqWS workspace root.", exception)
    }
    if (canonicalRoot != canonicalWorkspaceRoot) {
      throw stateIoFailure("The ReqWS workspace root changed identity.")
    }
    val ideaAttributes = try {
      Files.readAttributes(
        ideaDirectory,
        BasicFileAttributes::class.java,
        LinkOption.NOFOLLOW_LINKS,
      )
    } catch (exception: Exception) {
      throw stateIoFailure("The GoLand project metadata directory is unavailable.", exception)
    }
    if (!ideaAttributes.isDirectory || ideaAttributes.isSymbolicLink) {
      throw stateIoFailure("The GoLand project metadata directory must be a real directory.")
    }
    val canonicalIdea = try {
      ideaDirectory.toRealPath(LinkOption.NOFOLLOW_LINKS)
    } catch (exception: Exception) {
      throw stateIoFailure("Unable to canonicalize the GoLand project metadata directory.", exception)
    }
    if (canonicalIdea.parent != canonicalRoot || canonicalIdea.fileName.toString() != ".idea") {
      throw stateIoFailure("The GoLand project metadata directory escapes the workspace root.")
    }
  }
}

internal fun managedModelStateBinding(workspaceId: String, canonicalRoot: Path) =
  ManagedModelStateBinding(
    workspaceId = workspaceId,
    rootFingerprint = sha256Hex(canonicalRoot.toString()),
  )

internal fun currentJvmEpoch(): String = PROCESS_JVM_EPOCH

private fun sha256Hex(value: String): String = MessageDigest.getInstance("SHA-256")
  .digest(value.toByteArray(StandardCharsets.UTF_8))
  .joinToString(separator = "") { byte -> "%02x".format(byte.toInt() and 0xff) }

private fun validateDurableState(
  state: DurableManagedModelState,
  binding: ManagedModelStateBinding,
) {
  validateDurableStateShape(state)
  if (state.workspaceId != binding.workspaceId || state.rootFingerprint != binding.rootFingerprint) {
    throw stateIoFailure("The ReqWS managed-model state belongs to another workspace.")
  }
}

private fun validateDurableStateShape(state: DurableManagedModelState) {
  if (
    state.formatVersion != REQWS_MODEL_FILE_FORMAT_VERSION ||
    state.strategy != REQWS_MODEL_STRATEGY ||
    state.workspaceId.isEmpty() ||
    state.workspaceId.length > REQWS_MODEL_STATE_MAX_STRING ||
    containsUnpairedSurrogate(state.workspaceId) ||
    !SHA256_PATTERN.matches(state.rootFingerprint) ||
    state.generation < 0L ||
    !SHA256_PATTERN.matches(state.writerJvmEpoch) ||
    state.targetModuleName.length > REQWS_MODEL_STATE_MAX_STRING ||
    containsUnpairedSurrogate(state.targetModuleName) ||
    ((state.managedClaims.isNotEmpty() || state.recoveryClaims.isNotEmpty()) &&
      state.targetModuleName.isEmpty()) ||
    state.managedClaims.size + state.recoveryClaims.size > REQWS_MODEL_STATE_MAX_CLAIMS
  ) {
    throw VerifiedAtomicStateFileException("Invalid ReqWS managed-model state metadata.")
  }
  val managedPaths = hashSetOf<String>()
  val tokens = hashSetOf<String>()
  val recoveryKeys = hashSetOf<Pair<String, String>>()
  state.managedClaims.forEach { claim ->
    validateClaim(claim)
    if (!managedPaths.add(claim.relativePath) || !tokens.add(claim.markerToken)) {
      throw VerifiedAtomicStateFileException("Duplicate managed-model ownership claim.")
    }
  }
  state.recoveryClaims.forEach { claim ->
    validateClaim(claim)
    if (
      !recoveryKeys.add(claim.relativePath to claim.markerToken) ||
      !tokens.add(claim.markerToken)
    ) {
      throw VerifiedAtomicStateFileException("Duplicate managed-model recovery claim.")
    }
  }
}

private fun validateClaim(claim: DurableManagedClaim) {
  val relative = try {
    Path.of(claim.relativePath)
  } catch (exception: Exception) {
    throw VerifiedAtomicStateFileException("Invalid managed-model relative path.", exception)
  }
  if (
    claim.relativePath.isEmpty() ||
    claim.relativePath.length > REQWS_MODEL_STATE_MAX_STRING ||
    claim.relativePath == "." ||
    claim.relativePath == ".." ||
    containsUnpairedSurrogate(claim.relativePath) ||
    relative.isAbsolute ||
    relative.nameCount != 1 ||
    relative.normalize() != relative ||
    relative.fileName?.toString() != claim.relativePath ||
    !FILE_MARKER_TOKEN_PATTERN.matches(claim.markerToken)
  ) {
    throw VerifiedAtomicStateFileException("Invalid managed-model ownership claim.")
  }
}

private fun containsUnpairedSurrogate(value: String): Boolean {
  var index = 0
  while (index < value.length) {
    val character = value[index]
    when {
      Character.isHighSurrogate(character) -> {
        if (index + 1 >= value.length || !Character.isLowSurrogate(value[index + 1])) return true
        index += 2
      }
      Character.isLowSurrogate(character) -> return true
      else -> index++
    }
  }
  return false
}

private fun stateIoFailure(message: String, cause: Throwable? = null) =
  ProjectModelApplyException(ProjectModelErrorCode.INVALID_OWNERSHIP_STATE, message, cause)

private object DurableManagedModelStateCodec : AtomicStateCodec<DurableManagedModelState> {
  override fun encode(value: DurableManagedModelState): ByteArray = buildString {
    append('{')
    append("\"formatVersion\":").append(value.formatVersion).append(',')
    append("\"strategy\":").appendJsonString(value.strategy).append(',')
    append("\"workspaceId\":").appendJsonString(value.workspaceId).append(',')
    append("\"rootFingerprint\":").appendJsonString(value.rootFingerprint).append(',')
    append("\"generation\":").append(value.generation).append(',')
    append("\"writerJvmEpoch\":").appendJsonString(value.writerJvmEpoch).append(',')
    append("\"targetModuleName\":").appendJsonString(value.targetModuleName).append(',')
    append("\"managedClaims\":")
    appendClaims(value.managedClaims)
    append(',')
    append("\"recoveryClaims\":")
    appendClaims(value.recoveryClaims)
    append('}').append('\n')
  }.toByteArray(StandardCharsets.UTF_8)

  override fun decode(bytes: ByteArray): DurableManagedModelState {
    val decoder = StandardCharsets.UTF_8.newDecoder()
      .onMalformedInput(CodingErrorAction.REPORT)
      .onUnmappableCharacter(CodingErrorAction.REPORT)
    val text = decoder.decode(ByteBuffer.wrap(bytes)).toString()
    return StateJsonParser(text).parse()
  }

  private fun StringBuilder.appendClaims(claims: List<DurableManagedClaim>) {
    append('[')
    claims.forEachIndexed { index, claim ->
      if (index > 0) append(',')
      append("{\"relativePath\":")
      appendJsonString(claim.relativePath)
      append(",\"markerToken\":")
      appendJsonString(claim.markerToken)
      append('}')
    }
    append(']')
  }

  private fun StringBuilder.appendJsonString(value: String): StringBuilder {
    append('"')
    value.forEach { character ->
      when (character) {
        '"' -> append("\\\"")
        '\\' -> append("\\\\")
        '\b' -> append("\\b")
        '\u000C' -> append("\\f")
        '\n' -> append("\\n")
        '\r' -> append("\\r")
        '\t' -> append("\\t")
        else -> if (character.code < 0x20) {
          append("\\u").append(character.code.toString(16).padStart(4, '0'))
        } else {
          append(character)
        }
      }
    }
    return append('"')
  }
}

private class StateJsonParser(private val input: String) {
  private var offset = 0

  fun parse(): DurableManagedModelState {
    val fields = linkedMapOf<String, Any>()
    expect('{')
    skipWhitespace()
    if (!consume('}')) {
      while (true) {
        val key = parseString()
        if (fields.containsKey(key)) fail("Duplicate top-level field.")
        expect(':')
        fields[key] = when (key) {
          "formatVersion" -> parseLong()
          "strategy", "workspaceId", "rootFingerprint", "writerJvmEpoch", "targetModuleName" ->
            parseString()
          "generation" -> parseLong()
          "managedClaims", "recoveryClaims" -> parseClaims()
          else -> fail("Unknown top-level field.")
        }
        skipWhitespace()
        if (consume('}')) break
        expect(',')
      }
    }
    skipWhitespace()
    if (offset != input.length) fail("Trailing JSON content.")
    val expected = setOf(
      "formatVersion",
      "strategy",
      "workspaceId",
      "rootFingerprint",
      "generation",
      "writerJvmEpoch",
      "targetModuleName",
      "managedClaims",
      "recoveryClaims",
    )
    if (fields.keys != expected) fail("Missing managed-model state field.")
    val formatVersion = fields.getValue("formatVersion") as Long
    if (formatVersion !in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong()) {
      fail("Invalid format version.")
    }
    @Suppress("UNCHECKED_CAST")
    return DurableManagedModelState(
      formatVersion = formatVersion.toInt(),
      strategy = fields.getValue("strategy") as String,
      workspaceId = fields.getValue("workspaceId") as String,
      rootFingerprint = fields.getValue("rootFingerprint") as String,
      generation = fields.getValue("generation") as Long,
      writerJvmEpoch = fields.getValue("writerJvmEpoch") as String,
      targetModuleName = fields.getValue("targetModuleName") as String,
      managedClaims = fields.getValue("managedClaims") as List<DurableManagedClaim>,
      recoveryClaims = fields.getValue("recoveryClaims") as List<DurableManagedClaim>,
    )
  }

  private fun parseClaims(): List<DurableManagedClaim> {
    val claims = mutableListOf<DurableManagedClaim>()
    expect('[')
    skipWhitespace()
    if (consume(']')) return claims
    while (true) {
      if (claims.size >= REQWS_MODEL_STATE_MAX_CLAIMS) fail("Too many ownership claims.")
      expect('{')
      val fields = linkedMapOf<String, String>()
      skipWhitespace()
      if (consume('}')) fail("Empty ownership claim.")
      while (true) {
        val key = parseString()
        if (key !in setOf("relativePath", "markerToken") || fields.containsKey(key)) {
          fail("Invalid ownership claim field.")
        }
        expect(':')
        fields[key] = parseString()
        skipWhitespace()
        if (consume('}')) break
        expect(',')
      }
      if (fields.keys != setOf("relativePath", "markerToken")) {
        fail("Incomplete ownership claim.")
      }
      claims.add(
        DurableManagedClaim(
          relativePath = fields.getValue("relativePath"),
          markerToken = fields.getValue("markerToken"),
        ),
      )
      skipWhitespace()
      if (consume(']')) return claims
      expect(',')
    }
  }

  private fun parseLong(): Long {
    skipWhitespace()
    val start = offset
    if (peek() == '-') offset++
    val digitStart = offset
    while (peek()?.isDigit() == true) offset++
    if (digitStart == offset) fail("Invalid JSON integer.")
    val raw = input.substring(start, offset)
    if (
      raw == "-0" ||
      raw.startsWith("0") && raw.length > 1 ||
      raw.startsWith("-0") && raw.length > 2
    ) {
      fail("Non-canonical JSON integer.")
    }
    return raw.toLongOrNull() ?: fail("JSON integer is out of range.")
  }

  private fun parseString(): String {
    skipWhitespace()
    expectRaw('"')
    val result = StringBuilder()
    while (offset < input.length) {
      val character = input[offset++]
      when {
        character == '"' -> return result.toString()
        character == '\\' -> {
          if (offset >= input.length) fail("Incomplete JSON escape.")
          when (val escaped = input[offset++]) {
            '"', '\\', '/' -> result.append(escaped)
            'b' -> result.append('\b')
            'f' -> result.append('\u000C')
            'n' -> result.append('\n')
            'r' -> result.append('\r')
            't' -> result.append('\t')
            'u' -> {
              if (offset + 4 > input.length) fail("Incomplete Unicode escape.")
              val code = input.substring(offset, offset + 4).toIntOrNull(16)
                ?: fail("Invalid Unicode escape.")
              result.append(code.toChar())
              offset += 4
            }
            else -> fail("Invalid JSON escape.")
          }
        }
        character.code < 0x20 -> fail("Unescaped JSON control character.")
        else -> result.append(character)
      }
      if (result.length > REQWS_MODEL_STATE_MAX_STRING) fail("JSON string is too long.")
    }
    fail("Unterminated JSON string.")
  }

  private fun expect(character: Char) {
    skipWhitespace()
    expectRaw(character)
  }

  private fun expectRaw(character: Char) {
    if (offset >= input.length || input[offset] != character) {
      fail("Unexpected JSON token.")
    }
    offset++
  }

  private fun consume(character: Char): Boolean {
    skipWhitespace()
    if (offset < input.length && input[offset] == character) {
      offset++
      return true
    }
    return false
  }

  private fun skipWhitespace() {
    while (offset < input.length && input[offset] in setOf(' ', '\t', '\r', '\n')) offset++
  }

  private fun peek(): Char? = input.getOrNull(offset)

  private fun fail(message: String): Nothing = throw VerifiedAtomicStateFileException(message)
}
