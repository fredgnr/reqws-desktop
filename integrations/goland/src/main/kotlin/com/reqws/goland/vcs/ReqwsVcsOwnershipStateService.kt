package com.reqws.goland.vcs

import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.StoragePathMacros
import com.reqws.goland.persistence.AtomicStateCodec
import com.reqws.goland.persistence.VerifiedAtomicStateFile
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.channels.OverlappingFileLockException
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest
import java.util.UUID

/**
 * VCS ownership is stored outside the IntelliJ component store so a destructive mapping update
 * can be preceded by an independently forced, atomically verified revocation journal.
 */
@Service(Service.Level.PROJECT)
@State(
  name = "ReqwsVcsOwnershipState",
  storages = [Storage(StoragePathMacros.WORKSPACE_FILE)],
)
internal class ReqwsVcsOwnershipStateService(
  private val writerEpoch: String = newWriterEpoch(),
  private val beforeWriterLockAttempt: () -> Unit = {},
  private val afterWriterLockAcquired: () -> Unit = {},
) :
  PersistentStateComponent<ReqwsVcsOwnershipStateService.LegacyPersistedState> {
  private var legacyState = LegacyPersistedState()

  init {
    require(WRITER_EPOCH.matches(writerEpoch)) { "Invalid ReqWS VCS writer epoch" }
  }

  @Synchronized
  override fun getState(): LegacyPersistedState = legacyState.deepCopy()

  @Synchronized
  override fun loadState(state: LegacyPersistedState) {
    legacyState = state.deepCopy()
  }

  /** Read-only load. Legacy state is decoded here but is never published until a gated apply. */
  fun readForProject(projectRoot: Path, workspaceId: String): VcsOwnershipLoadResult {
    val binding = stateBinding(projectRoot, workspaceId)
    val file = stateFile(projectRoot)
    val persisted = try {
      file.read()?.also { state -> validatePersistedBinding(state, binding) }
    } catch (_: Exception) {
      return conflictLoadResult(binding)
    }
    val legacySnapshot = try {
      if (persisted == null) legacySnapshot() else null
    } catch (_: Exception) {
      return conflictLoadResult(binding)
    }
    if (persisted == null && legacySnapshot == null) {
      return VcsOwnershipLoadResult(
        ownership = emptyList(),
        pendingAdds = emptyList(),
        pendingRemovals = emptyList(),
        diagnostics = emptyList(),
        binding = binding,
        version = null,
        legacyMigration = null,
      )
    }

    val resolvedIdentities = hashSetOf<String>()
    fun resolve(relativeDirectory: String): OwnedPath? {
      val resolved = VcsPathIdentity.resolveOwned(projectRoot, relativeDirectory) ?: return null
      return resolved.takeIf { resolvedIdentities.add(it.lexicalIdentity) }
    }

    val ownership = ArrayList<VcsMappingOwnership>()
    val pendingAdds = ArrayList<VcsMappingPendingOwnership>()
    val pendingRemovals = ArrayList<VcsMappingPendingOwnership>()
    val stableMappings = if (persisted != null) {
      persisted.stableMappings
    } else {
      requireNotNull(legacySnapshot).managedMappings.map { entry ->
        require(entry.kind == "CREATED" || entry.kind == "BORROWED") {
          "Invalid legacy ReqWS VCS ownership kind"
        }
        PersistedMapping(entry.relativeDirectory, entry.kind)
      }
    }
    val pendingAddMappings = persisted?.pendingAdds.orEmpty()
    val pendingRemovalMappings = persisted?.pendingRemovals.orEmpty()
    val foreignWriter = persisted == null ||
      persisted.stateVersion == LEGACY_ATOMIC_STATE_VERSION ||
      persisted.writerEpoch != writerEpoch
    stableMappings.forEach { entry ->
      val resolved = resolve(entry.relativeDirectory) ?: return conflictLoadResult(binding)
      val persistedKind = try {
        VcsMappingOwnershipKind.valueOf(entry.kind)
      } catch (_: IllegalArgumentException) {
        return conflictLoadResult(binding)
      }
      // A plain path/Git/rootSettings match cannot authenticate an object across project-service
      // or JVM lifetimes. Foreign CREATED claims therefore lose deletion authority on load.
      val kind = if (foreignWriter && persistedKind == VcsMappingOwnershipKind.CREATED) {
        VcsMappingOwnershipKind.BORROWED
      } else {
        persistedKind
      }
      ownership.add(VcsMappingOwnership(resolved.relativeDirectory, kind))
    }
    pendingAddMappings.forEach { entry ->
      val resolved = resolve(entry.relativeDirectory) ?: return conflictLoadResult(binding)
      pendingAdds.add(VcsMappingPendingOwnership(resolved.relativeDirectory, entry.operationToken))
    }
    pendingRemovalMappings.forEach { entry ->
      val resolved = resolve(entry.relativeDirectory) ?: return conflictLoadResult(binding)
      pendingRemovals.add(VcsMappingPendingOwnership(resolved.relativeDirectory, entry.operationToken))
    }
    return VcsOwnershipLoadResult(
      ownership = ownership,
      pendingAdds = pendingAdds,
      pendingRemovals = pendingRemovals,
      diagnostics = emptyList(),
      binding = binding,
      version = persisted?.version(),
      legacyMigration = legacySnapshot,
    )
  }

  /**
   * Captures the generation/writer epoch observed by the initial load for the whole apply. Each
   * successful transition advances that exact fence; an external advance makes every remaining
   * commit from the old plan fail instead of silently rebasing at prepare time.
   */
  fun recorderForProject(
    projectRoot: Path,
    workspaceId: String,
    loaded: VcsOwnershipLoadResult,
    mutationGate: () -> VcsMappingApplyErrorCode? = { null },
  ): VcsMappingOwnershipRecorder {
    val binding = stateBinding(projectRoot, workspaceId)
    require(loaded.binding == binding) { "VCS ownership load belongs to another workspace" }
    val fence = Any()
    var expectedVersion = loaded.version
    var legacyMigration = loaded.legacyMigration
    return VcsMappingOwnershipRecorder { state ->
      val (expected, legacy) = synchronized(fence) {
        expectedVersion to legacyMigration
      }
      val replacement = prepareReplacementForProject(
        projectRoot = projectRoot,
        binding = binding,
        expectedVersion = expected,
        state = state,
        legacyMigration = legacy,
      )
      VcsMappingOwnershipCommit {
        ensureMutationAllowed(mutationGate)
        val persistedVersion = persistPreparedReplacement(replacement, mutationGate)
        synchronized(fence) {
          require(expectedVersion == expected) {
            "ReqWS VCS ownership plan advanced concurrently"
          }
          expectedVersion = persistedVersion
          legacyMigration = null
        }
      }
    }
  }

  /** Resolves all paths before an EDT mapping checkpoint and prepares immutable file contents. */
  internal fun prepareReplacementForProject(
    projectRoot: Path,
    binding: VcsOwnershipStateBinding,
    expectedVersion: VcsOwnershipFileVersion?,
    state: VcsMappingOwnershipState,
    legacyMigration: LegacyPersistedState? = null,
  ): PreparedReplacement {
    require(binding == stateBinding(projectRoot, binding.workspaceId)) {
      "VCS ownership binding does not match the project root"
    }
    require(expectedVersion?.generation != Long.MAX_VALUE) {
      "ReqWS VCS ownership generation is exhausted"
    }
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
    val replacement = PersistedState(
      stateVersion = CURRENT_STATE_VERSION,
      workspaceId = binding.workspaceId,
      workspaceRootFingerprint = binding.rootFingerprint,
      generation = (expectedVersion?.generation ?: 0L) + 1L,
      writerEpoch = writerEpoch,
      stableMappings = stable.sortedBy(PersistedMapping::relativeDirectory),
      pendingAdds = state.pendingAdds.map(::pending)
        .sortedBy(PersistedPendingMapping::relativeDirectory),
      pendingRemovals = state.pendingRemovals.map(::pending)
        .sortedBy(PersistedPendingMapping::relativeDirectory),
    )
    validatePersistedState(replacement)
    return PreparedReplacement(
      projectRoot = projectRoot,
      file = file,
      binding = binding,
      expectedVersion = expectedVersion,
      state = replacement,
      legacyMigration = legacyMigration?.deepCopy(),
    )
  }

  /** Holds an OS lock across read/check/atomic replace/read-back for cross-service/JVM fencing. */
  internal fun persistPreparedReplacement(
    replacement: PreparedReplacement,
    mutationGate: () -> VcsMappingApplyErrorCode? = { null },
  ): VcsOwnershipFileVersion {
    ensureMutationAllowed(mutationGate)
    ensureSafeStateLocation(replacement.projectRoot)
    val lockFile = replacement.projectRoot.resolve(STATE_DIRECTORY).resolve(LOCK_FILE_NAME)
    ensureSafeLockFile(lockFile)
    try {
      beforeWriterLockAttempt()
      FileChannel.open(
        lockFile,
        StandardOpenOption.CREATE,
        StandardOpenOption.WRITE,
        LinkOption.NOFOLLOW_LINKS,
      ).use { channel ->
        val fileLock = try {
          channel.tryLock()
        } catch (_: OverlappingFileLockException) {
          null
        } ?: error("Another ReqWS VCS ownership writer is active")
        fileLock.use {
          afterWriterLockAcquired()
          val current = replacement.file.read()
          current?.let { state -> validatePersistedBinding(state, replacement.binding) }
          require(current?.version() == replacement.expectedVersion) {
            "ReqWS VCS ownership generation or writer epoch changed before the prepared write"
          }
          ensureMutationAllowed(mutationGate)
          replacement.file.writeAndVerify(replacement.state)
          // The authoritative state is already durably committed at this point. A trust/dispose
          // flip must stop later platform mutation, but it cannot truthfully turn this checkpoint
          // into a failed write or leave the recorder on the previous generation. Only the
          // non-authoritative legacy mirror cleanup remains gated.
          if (mutationGate() == null) {
            replacement.legacyMigration?.let(::clearLegacyMigration)
          }
          return replacement.state.version()
        }
      }
    } catch (exception: VcsOwnershipMutationBlockedException) {
      throw exception
    } catch (exception: Exception) {
      throw IllegalStateException("Unable to persist ReqWS VCS ownership state", exception)
    }
  }

  internal class PreparedReplacement internal constructor(
    internal val projectRoot: Path,
    internal val file: VerifiedAtomicStateFile<PersistedState>,
    internal val binding: VcsOwnershipStateBinding,
    internal val expectedVersion: VcsOwnershipFileVersion?,
    internal val state: PersistedState,
    internal val legacyMigration: LegacyPersistedState?,
  )

  internal data class PersistedState(
    val stateVersion: Int,
    val workspaceId: String?,
    val workspaceRootFingerprint: String,
    val generation: Long,
    val writerEpoch: String?,
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

  private fun stateFile(projectRoot: Path): VerifiedAtomicStateFile<PersistedState> =
    VerifiedAtomicStateFile(
      file = projectRoot.resolve(STATE_DIRECTORY).resolve(STATE_FILE_NAME),
      maxBytes = MAX_STATE_BYTES,
      codec = VcsOwnershipStateCodec,
      validate = ::validatePersistedState,
    )

  @Synchronized
  private fun legacySnapshot(): LegacyPersistedState? {
    if (legacyState.managedMappings.isEmpty()) return null
    require(legacyState.stateVersion == LEGACY_STATE_VERSION) {
      "Unsupported legacy ReqWS VCS ownership state"
    }
    legacyState.managedMappings.forEach { entry ->
      require(entry.kind == "CREATED" || entry.kind == "BORROWED") {
        "Invalid legacy ReqWS VCS ownership kind"
      }
    }
    return legacyState.deepCopy()
  }

  @Synchronized
  private fun clearLegacyMigration(migrated: LegacyPersistedState) {
    if (legacyState == migrated) legacyState = LegacyPersistedState()
  }

  private fun stateBinding(projectRoot: Path, workspaceId: String): VcsOwnershipStateBinding {
    require(workspaceId.isNotBlank() && workspaceId.length <= MAX_STATE_STRING) {
      "Invalid ReqWS VCS workspace identity"
    }
    return VcsOwnershipStateBinding(
      workspaceId = workspaceId,
      rootFingerprint = rootFingerprint(projectRoot),
    )
  }

  private fun validatePersistedBinding(
    state: PersistedState,
    binding: VcsOwnershipStateBinding,
  ) {
    val matches = when (state.stateVersion) {
      CURRENT_STATE_VERSION -> state.workspaceId == binding.workspaceId &&
        state.workspaceRootFingerprint == binding.rootFingerprint
      LEGACY_ATOMIC_STATE_VERSION ->
        state.workspaceRootFingerprint == binding.rootFingerprint
      else -> false
    }
    require(matches) { "ReqWS VCS ownership belongs to a different workspace" }
  }

  private fun PersistedState.version() = VcsOwnershipFileVersion(
    stateVersion = stateVersion,
    generation = generation,
    writerEpoch = writerEpoch,
  )

  private fun ensureMutationAllowed(mutationGate: () -> VcsMappingApplyErrorCode?) {
    mutationGate()?.let { code -> throw VcsOwnershipMutationBlockedException(code) }
  }

  private fun ensureSafeStateLocation(projectRoot: Path) {
    val normalizedRoot = projectRoot.toAbsolutePath().normalize()
    val ideaDirectory = normalizedRoot.resolve(STATE_DIRECTORY)
    val rootAttributes = Files.readAttributes(
      normalizedRoot,
      BasicFileAttributes::class.java,
      LinkOption.NOFOLLOW_LINKS,
    )
    require(rootAttributes.isDirectory && !rootAttributes.isSymbolicLink) {
      "ReqWS VCS workspace root must be a real directory"
    }
    val ideaAttributes = Files.readAttributes(
      ideaDirectory,
      BasicFileAttributes::class.java,
      LinkOption.NOFOLLOW_LINKS,
    )
    require(ideaAttributes.isDirectory && !ideaAttributes.isSymbolicLink) {
      "ReqWS VCS state directory must be a real directory"
    }
    val canonicalRoot = normalizedRoot.toRealPath(LinkOption.NOFOLLOW_LINKS)
    val canonicalIdea = ideaDirectory.toRealPath(LinkOption.NOFOLLOW_LINKS)
    require(
      canonicalRoot == normalizedRoot &&
        canonicalIdea.parent == canonicalRoot &&
        canonicalIdea.fileName.toString() == STATE_DIRECTORY,
    ) { "ReqWS VCS state directory escapes the workspace root" }
  }

  private fun ensureSafeLockFile(lockFile: Path) {
    val attributes = try {
      Files.readAttributes(lockFile, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
    } catch (_: NoSuchFileException) {
      return
    }
    require(attributes.isRegularFile && !attributes.isSymbolicLink) {
      "ReqWS VCS ownership lock must be a regular file"
    }
  }

  private fun conflictLoadResult(binding: VcsOwnershipStateBinding) = VcsOwnershipLoadResult(
    ownership = emptyList(),
    pendingAdds = emptyList(),
    pendingRemovals = emptyList(),
    diagnostics = listOf(VcsMappingDiagnostic(VcsMappingDiagnosticCode.OWNERSHIP_CONFLICT)),
    binding = binding,
    version = null,
    legacyMigration = null,
  )

  companion object {
    const val CURRENT_STATE_VERSION = 3
    const val STATE_FILE_NAME = "reqws-vcs-ownership.json"
    internal const val LOCK_FILE_NAME = ".$STATE_FILE_NAME.lock"
    private const val STATE_DIRECTORY = ".idea"
    private const val MAX_STATE_BYTES = 256 * 1024
    private const val MAX_STATE_STRING = 1024
    private val OPERATION_TOKEN = Regex("[0-9a-f]{32}")
    private val WRITER_EPOCH = Regex("[0-9a-f]{32}")
    private val ROOT_FINGERPRINT = Regex("[0-9a-f]{64}")

    private fun validatePersistedState(state: PersistedState) {
      require(
        state.stateVersion == CURRENT_STATE_VERSION ||
          state.stateVersion == LEGACY_ATOMIC_STATE_VERSION,
      ) { "Unsupported ReqWS VCS ownership state version" }
      if (state.stateVersion == CURRENT_STATE_VERSION) {
        require(
          !state.workspaceId.isNullOrBlank() &&
            state.workspaceId.length <= MAX_STATE_STRING,
        ) { "Invalid ReqWS VCS workspace identity" }
        require(state.writerEpoch != null && WRITER_EPOCH.matches(state.writerEpoch)) {
          "Invalid ReqWS VCS ownership writer epoch"
        }
      } else {
        require(state.workspaceId == null && state.writerEpoch == null) {
          "Legacy ReqWS VCS ownership state must remain unbound"
        }
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
    private const val LEGACY_ATOMIC_STATE_VERSION = 2
    private const val LEGACY_STATE_VERSION = 1

    private fun newWriterEpoch(): String = UUID.randomUUID().toString().replace("-", "")

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
  internal val binding: VcsOwnershipStateBinding,
  internal val version: VcsOwnershipFileVersion?,
  internal val legacyMigration: ReqwsVcsOwnershipStateService.LegacyPersistedState?,
)

internal data class VcsOwnershipStateBinding(
  val workspaceId: String,
  val rootFingerprint: String,
)

internal data class VcsOwnershipFileVersion(
  val stateVersion: Int,
  val generation: Long,
  val writerEpoch: String?,
)

private object VcsOwnershipStateCodec : AtomicStateCodec<ReqwsVcsOwnershipStateService.PersistedState> {
  override fun encode(value: ReqwsVcsOwnershipStateService.PersistedState): ByteArray = buildString {
    require(value.stateVersion == ReqwsVcsOwnershipStateService.CURRENT_STATE_VERSION) {
      "Only current ReqWS VCS ownership state can be written"
    }
    append("{\"stateVersion\":")
    append(value.stateVersion)
    append(",\"workspaceId\":")
    appendJsonString(requireNotNull(value.workspaceId))
    append(",\"workspaceRootFingerprint\":")
    appendJsonString(value.workspaceRootFingerprint)
    append(",\"generation\":")
    append(value.generation)
    append(",\"writerEpoch\":")
    appendJsonString(requireNotNull(value.writerEpoch))
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
    val workspaceId: String?
    val workspaceRootFingerprint: String
    val generation: Long
    val writerEpoch: String?
    when (version) {
      2 -> {
        cursor.expect(',')
        cursor.expectKey("generation")
        generation = cursor.readPositiveLong()
        cursor.expect(',')
        cursor.expectKey("workspaceRootFingerprint")
        workspaceRootFingerprint = cursor.readString()
        workspaceId = null
        writerEpoch = null
      }
      ReqwsVcsOwnershipStateService.CURRENT_STATE_VERSION -> {
        cursor.expect(',')
        cursor.expectKey("workspaceId")
        workspaceId = cursor.readString()
        cursor.expect(',')
        cursor.expectKey("workspaceRootFingerprint")
        workspaceRootFingerprint = cursor.readString()
        cursor.expect(',')
        cursor.expectKey("generation")
        generation = cursor.readPositiveLong()
        cursor.expect(',')
        cursor.expectKey("writerEpoch")
        writerEpoch = cursor.readString()
      }
      else -> error("Unsupported ReqWS VCS ownership state version")
    }
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
      workspaceId = workspaceId,
      workspaceRootFingerprint = workspaceRootFingerprint,
      generation = generation,
      writerEpoch = writerEpoch,
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
