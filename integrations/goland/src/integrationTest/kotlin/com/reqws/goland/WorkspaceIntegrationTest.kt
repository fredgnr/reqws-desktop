package com.reqws.goland

import com.intellij.driver.client.Driver
import com.intellij.driver.client.service
import com.intellij.driver.sdk.getModules
import com.intellij.driver.sdk.openToolWindow
import com.intellij.driver.sdk.singleProject
import com.intellij.driver.sdk.ui.components.common.ideFrame
import com.intellij.driver.sdk.ui.components.common.toolwindows.projectView
import com.intellij.driver.sdk.waitFor
import com.intellij.ide.starter.ide.IDETestContext
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.writeText
import kotlin.time.Duration.Companion.seconds

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class WorkspaceIntegrationTest {
  private val host = IdeScenarioHost()
  private val root get() = host.root

  @BeforeAll
  fun prepareEnvironment() = host.prepare()

  @Test
  fun loadingAndProjectTree() {
    val fixture = WorkspaceFixture(root)
    withIde(context("loading", fixture.shell)) {
      assertProjection(fixture, setOf("repo-a", "repo-b"))
    }
    val ordinary = Files.createTempDirectory(root, "ordinary-")
    ordinary.resolve("readme.txt").writeText("ordinary project\n")
    withIde(context("ordinary", ordinary)) {
      waitFor("ordinary project detection completed without activating ReqWS", 30.seconds) {
        service<ReqwsRemoteService>(singleProject()).getState().getLifecycle().name() == "INACTIVE"
      }
      assertFalse(getModules().any { it.getName().startsWith("reqws-", ignoreCase = true) })
      openToolWindow("Project")
      val tree = ideFrame().projectView().projectViewTree
      waitFor("ordinary project file is visible in the Project tree", 30.seconds) {
        tree.expandAll(10.seconds)
        tree.collectExpandedPaths().any { it.path.last() == "readme.txt" }
      }
    }
    assertFalse(Files.exists(ordinary.resolve(".idea/reqws-loaded-roots.json")))
    assertFalse(Files.exists(ordinary.resolve(".reqws")))
    fixture.assertDiskPreserved()
  }

  @Test
  fun atomicSelectionAutomaticallyRefreshes() {
    val fixture = WorkspaceFixture(root)
    withIde(context("refresh", fixture.shell)) {
      assertProjection(fixture, setOf("repo-a", "repo-b"))
      for (selection in listOf(listOf("repo-a"), emptyList(), listOf("repo-a", "repo-b"))) {
        // Only the host's atomic Desktop-protocol write triggers this chain.
        // Deliberately never call refresh, refreshAutomatically, Sync Now or VFS refresh.
        fixture.select(selection)
        assertProjection(fixture, selection.toSet())
        fixture.assertDiskPreserved()
      }
    }
  }

  @Test
  fun emptyAndNonemptySurviveColdProcesses() {
    repeat(2) { iteration ->
      val fixture = WorkspaceFixture(root)
      fixture.select(emptyList())
      val context = context("cold-$iteration", fixture.shell)
      withIde(context) { assertProjection(fixture, emptySet()) }
      // Reuse only this context's fixture/config. runIdeWithDriver launches a new PID.
      withIde(context) {
        assertProjection(fixture, emptySet())
        fixture.select(listOf("repo-a", "repo-b"))
        assertProjection(fixture, setOf("repo-a", "repo-b"))
      }
      withIde(context) { assertProjection(fixture, setOf("repo-a", "repo-b")) }
      fixture.assertDiskPreserved()
    }
  }

  private fun context(name: String, project: Path) = host.context(name, project)
  private fun withIde(context: IDETestContext, block: Driver.() -> Unit) = host.withIde(context, block = block)
  private fun Driver.assertProjection(fixture: ProjectionFixture, selected: Set<String>) =
    host.assertProjection(this, fixture, selected)
}
