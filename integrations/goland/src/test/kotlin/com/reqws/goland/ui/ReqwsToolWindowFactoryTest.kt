package com.reqws.goland.ui

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.reqws.goland.project.ReqwsProjectDetector
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.coroutines.runBlocking

class ReqwsToolWindowFactoryTest : BasePlatformTestCase() {
  fun testRemainsApplicableToAFileBasedProjectBeforeManifestCreation() = runBlocking {
    assertNotNull(project.basePath)

    assertTrue(ReqwsToolWindowFactory().isApplicableAsync(project))
  }

  fun testIsInitiallyUnavailableUntilTheFixedManifestEntryExists() {
    val factory = ReqwsToolWindowFactory()
    val projectRoot = Path.of(requireNotNull(project.basePath))
    val manifestPath = ReqwsProjectDetector.manifestPath(projectRoot)

    // Platform tests reuse the project base path across methods in this suite. Establish the
    // absent-manifest precondition explicitly so service tests that create a valid manifest do not
    // make this availability assertion order-dependent.
    Files.deleteIfExists(manifestPath)
    assertFalse(factory.shouldBeAvailable(project))

    try {
      Files.createDirectories(manifestPath.parent)
      Files.writeString(manifestPath, "{}")

      assertTrue(factory.shouldBeAvailable(project))
    } finally {
      Files.deleteIfExists(manifestPath)
    }
  }
}
