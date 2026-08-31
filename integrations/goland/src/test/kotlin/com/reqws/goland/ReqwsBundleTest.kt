package com.reqws.goland

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale
import java.util.ResourceBundle

class ReqwsBundleTest {
  @Test
  fun `english and simplified Chinese bundles have identical non-empty keys`() {
    val english = ResourceBundle.getBundle("messages.ReqwsBundle", Locale.ROOT)
    val chinese = ResourceBundle.getBundle("messages.ReqwsBundle", Locale.SIMPLIFIED_CHINESE)

    assertEquals(english.keySet(), chinese.keySet())
    english.keySet().forEach { key ->
      assertTrue(english.getString(key).isNotBlank())
      assertTrue(chinese.getString(key).isNotBlank())
    }
  }
}
