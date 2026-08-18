package com.reqws.goland.projectmodel

import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.StoragePathMacros

internal const val REQWS_MODEL_STATE_VERSION = 4
internal const val REQWS_LEGACY_MODEL_STATE_VERSION = 2
internal const val REQWS_LEGACY_JOURNAL_STATE_VERSION = 3
internal const val REQWS_MODEL_STRATEGY = "workspace-root-excludes"

internal data class ManagedExcludeOwnership(
  val relativePath: String,
  val markerToken: String,
)

internal data class ManagedModelOwnership(
  val stateVersion: Int,
  val strategy: String,
  val targetModuleName: String,
  val managedExcludes: List<ManagedExcludeOwnership>,
  val pendingAdds: List<ManagedExcludeOwnership>,
  val pendingRemovals: List<ManagedExcludeOwnership>,
  val recoveryClaims: List<ManagedExcludeOwnership>,
)

@Service(Service.Level.PROJECT)
@State(
  name = "ReqwsManagedProjectModel",
  storages = [Storage(StoragePathMacros.WORKSPACE_FILE)],
)
class ReqwsManagedModelState : PersistentStateComponent<ReqwsManagedModelState.Data> {
  class PersistedManagedExclude {
    var relativePath: String = ""
    var markerToken: String = ""

    internal fun deepCopy(): PersistedManagedExclude = PersistedManagedExclude().also { copy ->
      copy.relativePath = relativePath
      copy.markerToken = markerToken
    }
  }

  class Data {
    var stateVersion: Int = REQWS_MODEL_STATE_VERSION
    var strategy: String = REQWS_MODEL_STRATEGY
    var targetModuleName: String = ""
    var managedExcludes: MutableList<PersistedManagedExclude> = mutableListOf()
    var pendingAdds: MutableList<PersistedManagedExclude> = mutableListOf()
    var pendingRemovals: MutableList<PersistedManagedExclude> = mutableListOf()
    var recoveryClaims: MutableList<PersistedManagedExclude> = mutableListOf()

    internal fun deepCopy(): Data = Data().also { copy ->
      copy.stateVersion = stateVersion
      copy.strategy = strategy
      copy.targetModuleName = targetModuleName
      copy.managedExcludes = managedExcludes.map { it.deepCopy() }.toMutableList()
      copy.pendingAdds = pendingAdds.map { it.deepCopy() }.toMutableList()
      copy.pendingRemovals = pendingRemovals.map { it.deepCopy() }.toMutableList()
      copy.recoveryClaims = recoveryClaims.map { it.deepCopy() }.toMutableList()
    }
  }

  private var data = Data()

  @Synchronized
  override fun getState(): Data = data.deepCopy()

  @Synchronized
  override fun loadState(state: Data) {
    data = state.deepCopy()
  }

  @Synchronized
  internal fun ownership(): ManagedModelOwnership = ManagedModelOwnership(
    stateVersion = data.stateVersion,
    strategy = data.strategy,
    targetModuleName = data.targetModuleName,
    managedExcludes = data.managedExcludes.map { persisted ->
      ManagedExcludeOwnership(
        relativePath = persisted.relativePath,
        markerToken = persisted.markerToken,
      )
    },
    pendingAdds = data.pendingAdds.map { persisted ->
      ManagedExcludeOwnership(
        relativePath = persisted.relativePath,
        markerToken = persisted.markerToken,
      )
    },
    pendingRemovals = data.pendingRemovals.map { persisted ->
      ManagedExcludeOwnership(
        relativePath = persisted.relativePath,
        markerToken = persisted.markerToken,
      )
    },
    recoveryClaims = data.recoveryClaims.map { persisted ->
      ManagedExcludeOwnership(
        relativePath = persisted.relativePath,
        markerToken = persisted.markerToken,
      )
    },
  )

  @Synchronized
  internal fun replaceOwnership(
    moduleName: String,
    managedExcludes: Map<String, String>,
    pendingAdds: Map<String, String> = emptyMap(),
    pendingRemovals: Map<String, String> = emptyMap(),
    recoveryClaims: Collection<ManagedExcludeOwnership> = emptyList(),
  ) {
    data = Data().also { next ->
      // This mutator remains only for v2/v3 migration tests and old persisted state. Production
      // ownership is written through VerifiedManagedModelStateRepository and mirrored below.
      next.stateVersion = REQWS_LEGACY_JOURNAL_STATE_VERSION
      next.targetModuleName = moduleName
      next.managedExcludes = persistedClaims(managedExcludes)
      next.pendingAdds = persistedClaims(pendingAdds)
      next.pendingRemovals = persistedClaims(pendingRemovals)
      next.recoveryClaims = persistedClaims(recoveryClaims)
    }
  }

  @Synchronized
  internal fun replaceExternalMirror(
    moduleName: String,
    managedClaims: Collection<DurableManagedClaim>,
    recoveryClaims: Collection<DurableManagedClaim>,
  ) {
    data = Data().also { next ->
      next.targetModuleName = moduleName
      next.managedExcludes = persistedClaims(
        managedClaims.associate { claim -> claim.relativePath to claim.markerToken },
      )
      next.recoveryClaims = persistedClaims(
        recoveryClaims.map { claim ->
          ManagedExcludeOwnership(claim.relativePath, claim.markerToken)
        },
      )
    }
  }

  private fun persistedClaims(claims: Map<String, String>): MutableList<PersistedManagedExclude> =
    claims.entries
      .sortedBy(Map.Entry<String, String>::key)
      .map { (relativePath, markerToken) ->
        PersistedManagedExclude().also { persisted ->
          persisted.relativePath = relativePath
          persisted.markerToken = markerToken
        }
      }
      .toMutableList()

  private fun persistedClaims(
    claims: Collection<ManagedExcludeOwnership>,
  ): MutableList<PersistedManagedExclude> = claims
    .sortedWith(compareBy(ManagedExcludeOwnership::relativePath, ManagedExcludeOwnership::markerToken))
    .map { claim ->
      PersistedManagedExclude().also { persisted ->
        persisted.relativePath = claim.relativePath
        persisted.markerToken = claim.markerToken
      }
    }
    .toMutableList()
}
