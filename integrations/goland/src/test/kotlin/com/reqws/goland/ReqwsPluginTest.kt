package com.reqws.goland

import org.junit.Assert.assertEquals
import org.junit.Test

class ReqwsPluginTest {
  @Test
  fun `declares stable plugin identity`() {
    assertEquals("com.reqws.workspace", ReqwsPlugin.ID)
    assertEquals("0.2.0", ReqwsPlugin.VERSION)
  }
}
