package com.reqws.goland.loading.model

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.reqws.goland.loading.contract.LoadingSnapshot
import com.reqws.goland.loading.contract.boundDirectory
import com.reqws.goland.persistence.AtomicStateCodec
import com.reqws.goland.persistence.VerifiedAtomicStateFile
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.nio.ByteBuffer
import java.nio.file.Path

internal data class RootClaim(
  val repositoryId: String,
  val relativePath: String,
  val nonce: String,
  val rootKey: String,
  val gitKey: String,
)

internal data class LoadedRootsJournal(
  val workspaceId: String,
  val bindingId: String,
  val workspaceRoot: String,
  val shell: String,
  val shellKey: String,
  val ideaKey: String,
  val moduleName: String,
  val moduleFile: String,
  val claims: List<RootClaim>,
  val pendingAdds: List<RootClaim>,
  val pendingRemoves: List<RootClaim>,
)

internal object LoadedRootsCodec : AtomicStateCodec<LoadedRootsJournal> {
  override fun encode(value: LoadedRootsJournal): ByteArray {
    val root = JsonObject()
    root.addProperty("formatVersion", 1)
    for ((key, text) in mapOf(
      "workspaceId" to value.workspaceId, "bindingId" to value.bindingId,
      "workspaceRoot" to value.workspaceRoot, "shell" to value.shell, "shellKey" to value.shellKey,
      "ideaKey" to value.ideaKey, "moduleName" to value.moduleName, "moduleFile" to value.moduleFile,
    )) root.addProperty(key, text)
    fun claims(values: List<RootClaim>) = JsonArray().also { array ->
      values.forEach { claim -> array.add(JsonObject().also { item ->
        item.addProperty("repositoryId", claim.repositoryId)
        item.addProperty("relativePath", claim.relativePath)
        item.addProperty("nonce", claim.nonce)
        item.addProperty("rootKey", claim.rootKey)
        item.addProperty("gitKey", claim.gitKey)
      }) }
    }
    root.add("claims", claims(value.claims))
    root.add("pendingAdds", claims(value.pendingAdds))
    root.add("pendingRemoves", claims(value.pendingRemoves))
    return (root.toString() + "\n").toByteArray(StandardCharsets.UTF_8)
  }

  override fun decode(bytes: ByteArray): LoadedRootsJournal {
    val text = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
      .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
    val root = JsonParser.parseString(text).asJsonObject
    require(root.keySet() == setOf("formatVersion", "workspaceId", "bindingId", "workspaceRoot", "shell", "shellKey", "ideaKey", "moduleName", "moduleFile", "claims", "pendingAdds", "pendingRemoves"))
    require(root["formatVersion"].asJsonPrimitive.isNumber && root["formatVersion"].asString == "1")
    fun JsonObject.string(key: String): String {
      require(get(key).asJsonPrimitive.isString)
      return get(key).asString.also { require(it.isNotEmpty() && it.length <= 16384) }
    }
    fun claims(key: String): List<RootClaim> = root.getAsJsonArray(key).map { element ->
      val item = element.asJsonObject
      require(item.keySet() == setOf("repositoryId", "relativePath", "nonce", "rootKey", "gitKey"))
      RootClaim(item.string("repositoryId"), item.string("relativePath"), item.string("nonce"), item.string("rootKey"), item.string("gitKey")).also {
        require(it.repositoryId.length <= 200)
        require(it.relativePath !in listOf(".", "..", ".reqws", ".idea") && !it.relativePath.contains('/') && !it.relativePath.contains('\\'))
        require(Regex("^[0-9a-f]{32}$").matches(it.nonce))
      }
    }.also { values ->
      require(values.size <= 4096 && values.distinctBy { it.relativePath }.size == values.size && values.distinctBy { it.nonce }.size == values.size)
    }
    return LoadedRootsJournal(root.string("workspaceId"), root.string("bindingId"), root.string("workspaceRoot"), root.string("shell"), root.string("shellKey"), root.string("ideaKey"), root.string("moduleName"), root.string("moduleFile"), claims("claims"), claims("pendingAdds"), claims("pendingRemoves"))
  }
}

internal fun rootsJournalFile(shell: Path) = VerifiedAtomicStateFile(
  shell.resolve(".idea/reqws-loaded-roots.json"), 1024 * 1024, LoadedRootsCodec,
)

internal fun LoadedRootsJournal.verifyBinding(snapshot: LoadingSnapshot) {
  val binding = snapshot.binding
  require(workspaceId == binding.workspaceId && bindingId == binding.bindingId && workspaceRoot == binding.workspaceRoot.toString() && shell == binding.shell.toString())
  require(shellKey == boundDirectory(binding.shell).fileKey && ideaKey == boundDirectory(binding.shell.resolve(".idea")).fileKey)
  require(moduleName == "ReqWS-${binding.bindingId}" && moduleFile == binding.shell.resolve(".idea/reqws/$moduleName.iml").toString())
  val byPath = (claims + pendingAdds + pendingRemoves).groupBy { it.relativePath }
  require(byPath.values.all { it.distinct().size == 1 }) { "Conflicting claims for one root." }
  require((claims + pendingAdds + pendingRemoves).distinct().distinctBy { it.nonce }.size == byPath.size)
}
