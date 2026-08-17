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

    assertFalse(factory.shouldBeAvailable(project))

    Files.createDirectories(manifestPath.parent)
    Files.writeString(manifestPath, "{}")

    assertTrue(factory.shouldBeAvailable(project))
  }
}
