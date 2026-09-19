package com.reqws.goland.loading

import com.reqws.goland.loading.contract.BindingException
import com.reqws.goland.loading.contract.GoLandProjectParser
import com.reqws.goland.loading.contract.GoLandSelection
import com.reqws.goland.loading.contract.resolveGoLandSelection
import com.reqws.goland.manifest.WorkspaceRepository
import com.google.gson.JsonParser
import org.junit.Assert.*
import org.junit.Test

class GoLandProjectParserTest {
  @Test
  fun sharedContractCorpus() {
    val text = requireNotNull(javaClass.getResource("/contracts/goland-project.json")).readText()
    val corpus = JsonParser.parseString(text).asJsonObject
    val cases = corpus.getAsJsonArray("cases")
    assertTrue(cases.size() >= 30)
    cases.forEach { item ->
      val case = item.asJsonObject
      val valid = case.get("valid").asBoolean
      val result = runCatching { GoLandProjectParser.parse(case.get("input").toString().toByteArray()) }
      assertEquals(case.get("name").asString, valid, result.isSuccess)
    }
  }

  @Test
  fun selectionUsesManifestOrderAndNeverDefaultsEmptyToAll() {
    val members = (1..3).map { WorkspaceRepository("repo_$it", "repo$it", "https://example.com/repo$it.git", "main", "repo$it") }
    assertEquals(members, resolveGoLandSelection(members, GoLandSelection.All))
    assertEquals(emptyList<WorkspaceRepository>(), resolveGoLandSelection(members, GoLandSelection.Selected(emptyList())))
    assertEquals(members.take(2), resolveGoLandSelection(members, GoLandSelection.Selected(listOf("repo_2", "stale", "repo_1"))))
  }

  @Test
  fun rejectsOversizeAndMalformedUtf8() {
    for (bytes in listOf(ByteArray(GoLandProjectParser.MAX_BYTES + 1), byteArrayOf(0xc3.toByte(), 0x28))) {
      assertThrows(BindingException::class.java) { GoLandProjectParser.parse(bytes) }
    }
  }
}
