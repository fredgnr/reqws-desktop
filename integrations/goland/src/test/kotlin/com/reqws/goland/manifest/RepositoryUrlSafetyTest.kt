package com.reqws.goland.manifest

import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets

class RepositoryUrlSafetyTest {
  @Test
  fun `matches every case in the shared repository URL contract`() {
    val resource = checkNotNull(
      javaClass.getResourceAsStream("/contracts/repository-url-safety.json"),
    ) { "Missing repository URL safety contract" }
    val document = resource.use { input ->
      InputStreamReader(input, StandardCharsets.UTF_8).use(JsonParser::parseReader)
    }.asJsonObject

    assertEquals(1, document.get("schemaVersion").asInt)
    val cases = document.getAsJsonArray("cases")
    val names = cases.map { it.asJsonObject.get("name").asString }
    assertEquals(names.size, names.toSet().size)
    assertTrue(
      "The shared contract must retain the URL compatibility boundary cases",
      names.containsAll(
        setOf(
          "legacy-compatible HTTPS empty port",
          "legacy-compatible HTTPS empty userinfo",
          "legacy-compatible percent-encoded UTF-8 HTTPS host",
          "legacy-compatible interior empty DNS label",
          "HTTPS IPv4-embedded IPv6 authority",
          "SSH percent-encoded colon username",
          "SSH multiple-at username",
          "legacy-compatible HTTPS empty password boundary",
          "legacy-compatible SSH empty password boundary",
          "legacy-compatible named SSH empty password boundary",
          "invalid percent-encoded UTF-8 authority host",
        ),
      ),
    )

    cases.forEach { element ->
      val testCase = element.asJsonObject
      val name = testCase.get("name").asString
      val url = testCase.get("url").asString
      val expected = testCase.get("safe").asBoolean
      assertEquals(name, expected, RepositoryUrlSafety.isSafe(url))
    }
  }
}
