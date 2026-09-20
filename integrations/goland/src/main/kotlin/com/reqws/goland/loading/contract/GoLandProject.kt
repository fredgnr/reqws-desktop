package com.reqws.goland.loading.contract

import com.reqws.goland.manifest.JsonArray
import com.reqws.goland.manifest.JsonNumber
import com.reqws.goland.manifest.JsonObject
import com.reqws.goland.manifest.JsonParser
import com.reqws.goland.manifest.JsonString
import com.reqws.goland.manifest.JsonValue
import com.reqws.goland.manifest.WorkspaceRepository
import com.reqws.goland.manifest.trimEcmaWhitespace
import java.math.BigDecimal
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.time.LocalDate

sealed interface GoLandSelection {
  data object All : GoLandSelection
  data class Selected(val repositoryIds: List<String>) : GoLandSelection
}

data class GoLandProject(
  val workspaceId: String,
  val bindingId: String,
  val revision: Long,
  val selection: GoLandSelection,
  val updatedAt: String,
)

class BindingException(message: String, cause: Throwable? = null) : IllegalArgumentException(message, cause)

object GoLandProjectParser {
  const val MAX_BYTES = 1024 * 1024
  private val uuid = Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[1-8][0-9a-fA-F]{3}-[89abAB][0-9a-fA-F]{3}-[0-9a-fA-F]{12}$")
  private val instant = Regex("^\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}(?::\\d{2}(?:\\.\\d+)?)?Z$")

  fun parse(bytes: ByteArray): GoLandProject {
    try {
      require(bytes.size <= MAX_BYTES) { "Binding exceeds 1 MiB." }
      val text = StandardCharsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT)
        .decode(ByteBuffer.wrap(bytes)).toString()
      val root = JsonParser(text).parseObjectDocument()
      require(root.keys == setOf("schemaVersion", "adapterProtocol", "workspaceId", "bindingId", "revision", "selection", "updatedAt"))
      require((root["schemaVersion"] as? JsonNumber)?.value?.compareTo(BigDecimal.ONE) == 0)
      require((root["adapterProtocol"] as? JsonNumber)?.value?.compareTo(BigDecimal.ONE) == 0)
      val revision = (root["revision"] as? JsonNumber)?.value?.longValueExact() ?: error("Missing revision")
      require(revision in 1..9007199254740991L)
      val bindingId = (root["bindingId"] as? JsonString)?.value ?: error("Missing binding ID")
      require(uuid.matches(bindingId))
      val selection = (root["selection"] as? JsonObject)?.values ?: error("Missing selection")
      val mode = (selection["mode"] as? JsonString)?.value
      val parsedSelection = when (mode) {
        "all" -> {
          require(selection.keys == setOf("mode"))
          GoLandSelection.All
        }
        "selected" -> {
          require(selection.keys == setOf("mode", "repositoryIds"))
          val ids = (selection["repositoryIds"] as? JsonArray)?.values?.map(::id) ?: error("Missing IDs")
          require(ids.distinct().size == ids.size)
          GoLandSelection.Selected(ids)
        }
        else -> error("Invalid selection mode")
      }
      val updatedAt = (root["updatedAt"] as? JsonString)?.value ?: error("Missing timestamp")
      require(instant.matches(updatedAt))
      LocalDate.parse(updatedAt.take(10))
      require(updatedAt.substring(11, 13).toInt() in 0..23)
      require(updatedAt.substring(14, 16).toInt() in 0..59)
      if (updatedAt.length > 17) require(updatedAt.substring(17, 19).toInt() in 0..59)
      return GoLandProject(id(root["workspaceId"]), bindingId, revision, parsedSelection, updatedAt)
    } catch (error: Exception) {
      throw BindingException("Invalid GoLand binding or selection.", error)
    }
  }

  private fun id(value: JsonValue?): String {
    val text = (value as? JsonString)?.value?.trimEcmaWhitespace() ?: error("Missing ID")
    require(text.isNotEmpty() && text.length <= 200)
    return text
  }
}

fun resolveGoLandSelection(members: List<WorkspaceRepository>, selection: GoLandSelection): List<WorkspaceRepository> =
  when (selection) {
    GoLandSelection.All -> members.toList()
    is GoLandSelection.Selected -> members.filter { it.catalogRepositoryId in selection.repositoryIds.toSet() }
  }
