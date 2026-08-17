package com.reqws.goland.vcs

import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.StoragePathMacros
import java.nio.file.Path

@Service(Service.Level.PROJECT)
@State(
  name = "ReqwsVcsOwnershipState",
  storages = [Storage(StoragePathMacros.WORKSPACE_FILE)],
)
internal class ReqwsVcsOwnershipStateService :
  PersistentStateComponent<ReqwsVcsOwnershipStateService.PersistedState> {
  private var persistedState = PersistedState()

  @Synchronized
  override fun getState(): PersistedState = persistedState.deepCopy()

  @Synchronized
  override fun loadState(state: PersistedState) {
    // Validation requiring the canonical workspace root happens in readForProject. Keep the raw
    // version so unsupported or malformed state cannot silently become trusted state.
    persistedState = state.deepCopy()
  }

  @Synchronized
  fun readForProject(projectRoot: Path): VcsOwnershipLoadResult {
    val state = persistedState
    if (state.stateVersion != CURRENT_STATE_VERSION) {
      return VcsOwnershipLoadResult(
        ownership = emptyList(),
        diagnostics = listOf(VcsMappingDiagnostic(VcsMappingDiagnosticCode.OWNERSHIP_CONFLICT)),
      )
    }

    val ownership = ArrayList<VcsMappingOwnership>()
    val diagnostics = ArrayList<VcsMappingDiagnostic>()
    state.managedMappings.forEach { entry ->
      val kind = try {
        VcsMappingOwnershipKind.valueOf(entry.kind)
      } catch (_: IllegalArgumentException) {
        null
      }
      if (kind == null || VcsPathIdentity.resolveOwned(projectRoot, entry.relativeDirectory) == null) {
        diagnostics.add(VcsMappingDiagnostic(VcsMappingDiagnosticCode.OWNERSHIP_CONFLICT))
      } else {
        ownership.add(VcsMappingOwnership(entry.relativeDirectory, kind))
      }
    }
    return VcsOwnershipLoadResult(ownership, diagnostics)
  }

  @Synchronized
  fun replaceForProject(projectRoot: Path, ownership: List<VcsMappingOwnership>) {
    val seen = HashSet<String>()
    val entries = ownership.map { item ->
      val resolved = requireNotNull(
        VcsPathIdentity.resolveOwned(projectRoot, item.relativeDirectory),
      ) { "VCS ownership must use a canonical workspace-relative path" }
      require(seen.add(resolved.lexicalIdentity)) {
        "VCS ownership must not contain duplicate paths"
      }
      PersistedMapping(
        relativeDirectory = resolved.relativeDirectory,
        kind = item.kind.name,
      )
    }
    persistedState = PersistedState(
      stateVersion = CURRENT_STATE_VERSION,
      managedMappings = entries.toMutableList(),
    )
  }

  internal data class PersistedState(
    var stateVersion: Int = CURRENT_STATE_VERSION,
    var managedMappings: MutableList<PersistedMapping> = mutableListOf(),
  ) {
    fun deepCopy(): PersistedState = copy(
      managedMappings = managedMappings.map { it.copy() }.toMutableList(),
    )
  }

  internal data class PersistedMapping(
    var relativeDirectory: String = "",
    var kind: String = "",
  )

  companion object {
    const val CURRENT_STATE_VERSION = 1
  }
}

internal data class VcsOwnershipLoadResult(
  val ownership: List<VcsMappingOwnership>,
  val diagnostics: List<VcsMappingDiagnostic>,
)
