package com.reqws.goland.ui

import java.awt.GraphicsEnvironment
import java.nio.file.Files
import java.nio.file.Path
import org.junit.Assert.*
import org.junit.Test

class ReqwsComposeEnvironmentTest {
  @Test fun requiresJetBrainsRuntimeAndAnActualGraphicsDevice() {
    assertTrue("Compose requires JetBrains Runtime", System.getProperty("java.vendor").contains("JetBrains"))
    assertFalse("Compose requires a graphical session", GraphicsEnvironment.isHeadless())
    val graphics = GraphicsEnvironment.getLocalGraphicsEnvironment()
    assertTrue("Compose requires a screen device", graphics.screenDevices.isNotEmpty())
    val output = Path.of(System.getProperty("reqws.compose.reports", "build/reports/compose-ui"), "environment.txt")
    Files.createDirectories(output.parent)
    Files.writeString(output, listOf("java.vendor", "java.home", "java.runtime.version", "os.name", "os.arch")
      .joinToString("\n") { "$it=${System.getProperty(it)}" } + "\ndisplay=${graphics.defaultScreenDevice.iDstring}\n")
  }
}

// Only the failure-probe task selects this class. A real failed assertion must
// make Gradle fail; infrastructure errors or missing tests are not evidence.
class ReqwsComposeFailureProbeTest {
  @Test fun deliberateAssertionMustFailTheBuild() {
    assertEquals("REQWS_COMPOSE_DELIBERATE_FAILURE", 1, 2)
  }
}
