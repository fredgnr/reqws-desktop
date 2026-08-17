package com.reqws.goland.projectmodel

import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.StoragePathMacros

internal const val REQWS_MODEL_STATE_VERSION = 2
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

    internal fun deepCopy(): Data = Data().also { copy ->
      copy.stateVersion = stateVersion
      copy.strategy = strategy
      copy.targetModuleName = targetModuleName
      copy.managedExcludes = managedExcludes.map { it.deepCopy() }.toMutableList()
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
  )

  @Synchronized
  internal fun replaceOwnership(moduleName: String, managedExcludes: Map<String, String>) {
    data = Data().also { next ->
      next.targetModuleName = moduleName
      next.managedExcludes = managedExcludes.entries
        .sortedBy(Map.Entry<String, String>::key)
        .map { (relativePath, markerToken) ->
          PersistedManagedExclude().also { persisted ->
            persisted.relativePath = relativePath
            persisted.markerToken = markerToken
          }
        }
        .toMutableList()
    }
  }
}
