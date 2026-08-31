package com.reqws.goland.project

import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.StoragePathMacros

private const val SYNC_STATE_VERSION = 1
private val SHA256_PATTERN = Regex("^[0-9a-f]{64}$")

@Service(Service.Level.PROJECT)
@State(
  name = "ReqwsSynchronization",
  storages = [Storage(StoragePathMacros.WORKSPACE_FILE)],
)
class ReqwsSyncPersistence : PersistentStateComponent<ReqwsSyncPersistence.Data> {
  class Data {
    var stateVersion: Int = SYNC_STATE_VERSION
    var lastAppliedDigest: String = ""
  }

  private var data = Data()

  @Synchronized
  override fun getState(): Data = data

  @Synchronized
  override fun loadState(state: Data) {
    data = state
  }

  @Synchronized
  internal fun lastAppliedDigest(): String? = data.lastAppliedDigest
    .takeIf { data.stateVersion == SYNC_STATE_VERSION && SHA256_PATTERN.matches(it) }

  @Synchronized
  internal fun markApplied(digestSha256: String) {
    require(SHA256_PATTERN.matches(digestSha256)) {
      "The applied manifest digest must be a lowercase SHA-256 value"
    }
    data = Data().also { next -> next.lastAppliedDigest = digestSha256 }
  }
}
