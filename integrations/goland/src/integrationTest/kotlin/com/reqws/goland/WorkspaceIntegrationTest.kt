package com.reqws.goland

import com.intellij.driver.client.Driver
import com.intellij.driver.client.utility
import com.intellij.driver.client.service
import com.intellij.driver.sdk.ProjectRootManager
import com.intellij.driver.sdk.getModules
import com.intellij.driver.sdk.getPlugin
import com.intellij.driver.sdk.isPluginLoaded
import com.intellij.driver.sdk.openToolWindow
import com.intellij.driver.sdk.singleProject
import com.intellij.driver.sdk.ui.components.common.ideFrame
import com.intellij.driver.sdk.ui.components.common.toolwindows.projectView
import com.intellij.driver.sdk.waitFor
import com.intellij.driver.sdk.isProjectOpened
import com.intellij.driver.sdk.ui.components.common.dialogs.licenseDialog
import com.intellij.ide.starter.driver.engine.runIdeWithDriver
import com.intellij.ide.starter.ide.IDETestContext
import com.intellij.ide.starter.project.LocalProjectInfo
import com.intellij.ide.starter.runner.Starter
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import kotlin.io.path.writeText
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class WorkspaceIntegrationTest {
  private val root = Path.of(System.getProperty("reqws.integration.root"))
  private val archive = Path.of(System.getProperty("reqws.plugin.archive"))
  private val environment = LocalIdeEnvironment()
  private val processes = mutableSetOf<Long>()
  private val candidateDigest = requireNotNull(System.getProperty("reqws.plugin.expectedSha256"))

  @BeforeAll
  fun prepareEnvironment() {
    environment.prepareHost()
    check(digest() == candidateDigest) { "Candidate changed before the host started" }
  }

  @Test
  fun loadingAndProjectTree() {
    val fixture = WorkspaceFixture(root)
    withIde(context("loading", fixture.shell)) {
      assertProjection(fixture, setOf("repo-a", "repo-b"))
    }
    val ordinary = Files.createTempDirectory(root, "ordinary-")
    ordinary.resolve("readme.txt").writeText("ordinary project\n")
    withIde(context("ordinary", ordinary)) {
      assertEquals("INACTIVE", service<ReqwsRemoteService>(singleProject()).getState().getLifecycle().name())
      assertFalse(getModules().any { it.getName().startsWith("reqws-") })
      openToolWindow("Project")
      val tree = ideFrame().projectView().projectViewTree
      tree.expandAll()
      assertTrue(tree.collectExpandedPaths().any { it.path.last() == "readme.txt" })
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

  private fun context(name: String, project: Path): IDETestContext = Starter.newContext(
    "reqws-$name", environment.testCase(LocalProjectInfo(project, isReusable = true)),
    preserveSystemDir = true,
  ).apply {
    environment.configure(this, project)
    pluginConfigurator.installPluginFromPath(archive).assertPluginIsInstalled("com.reqws.workspace")
  }

  private fun withIde(context: IDETestContext, block: Driver.() -> Unit) {
    check(digest() == candidateDigest) { "Candidate changed before launch" }
    environment.recordLaunchRequested()
    val run = try {
      context.runIdeWithDriver(runTimeout = 10.minutes)
    } catch (failure: Exception) {
      environment.reportPermissionBlock(failure)
      environment.block("IDE_START_UNAVAILABLE", "The isolated IDE could not start; inspect private diagnostics for authorization, graphical-session or permission blockers.", failure)
    }
    val pid = run.process.id.toLong()
    check(processes.add(pid)) { "Cold startup reused a process" }
    event(pid, "started")
    try {
      try {
        environment.verifyRuntimeDirectories(run.driver)
      } catch (failure: Exception) {
        environment.reportPermissionBlock(failure)
        environment.block("IDE_DRIVER_UNAVAILABLE", "The isolated IDE/Driver could not establish a verified session; inspect local startup, authorization and permission diagnostics.", failure)
      }
      val result = run.useDriverAndCloseIde(closeIdeTimeout = 1.minutes) {
        try {
          waitFor("local IDE project opened without an authorization dialog", 2.minutes) {
            if (licenseDialog().present()) environment.block("IDE_AUTHORIZATION_REQUIRED",
              "The dedicated test IDE requires authorization. Use the local prepare command and sign in, or configure the optional License Server.")
            isProjectOpened()
          }
        } catch (failure: SecurityException) {
          environment.block("IDE_PERMISSION_DENIED", "The local IDE/Driver requires graphical automation permissions.", failure)
        } catch (failure: Exception) {
          environment.reportPermissionBlock(failure)
          environment.block("PROJECT_START_UNRESOLVED", "The local IDE did not reach an opened project; authorization/startup readiness is unconfirmed. See private diagnostics.", failure)
        }
        assertTrue(isPluginLoaded("com.reqws.workspace"))
        assertEquals(System.getProperty("reqws.plugin.version"), getPlugin("com.reqws.workspace")?.getVersion())
        block()
      }
      check(result.exitCode == 0 && result.failureError == null) { "IDE process failed: ${result.failureError}" }
      check(!run.process.isAlive) { "IDE process leaked after shutdown" }
      environment.checkIdeErrors()
      check(digest() == candidateDigest) { "Candidate changed during integration" }
      event(pid, "passed")
    } catch (failure: Exception) {
      environment.reportPermissionBlock(failure)
      throw failure
    } finally {
      if (run.process.isAlive) {
        run.forceKill()
        event(pid, "forced-kill")
        error("IDE process required forced cleanup")
      }
      event(pid, "exited")
    }
  }

  private fun Driver.assertProjection(fixture: WorkspaceFixture, selected: Set<String>) {
    val reqws = service<ReqwsRemoteService>(singleProject())
    waitFor("revision ${fixture.revision} applied with live projection proof", 2.minutes) {
      val state = reqws.getState()
      val loading = state.getSnapshot()?.getLoading()
      loading != null && state.getLifecycle().name() in setOf("SYNCHRONIZED", "DEGRADED") &&
        loading.getProject().getRevision() == fixture.revision &&
        state.getValidatedProjectionDigest() == loading.getDigest() &&
        state.getLastAppliedDigest() == loading.getDigest()
    }
    val roots = withReadAction {
      service<ProjectRootManager>(singleProject()).getContentRoots().map { it.getPath() }.toSet()
    }
    for (name in setOf("repo-a", "repo-b")) {
      assertEquals(name in selected, fixture.root.resolve(name).toString() in roots)
    }
    assertTrue(fixture.root.resolve("user-content").toString() in roots)
    withReadAction {
      val index = service<RemoteProjectFileIndex>(singleProject())
      val files = utility<RemoteLocalFileSystem>().getInstance()
      for (name in setOf("repo-a", "repo-b", "user-content")) {
        val file = requireNotNull(files.findFileByPath(fixture.root.resolve(name).toString()))
        assertEquals(name in selected || name == "user-content", index.isInContent(file))
      }
      val shellFile = requireNotNull(files.findFileByPath(fixture.shell.toString()))
      assertTrue(index.isExcluded(shellFile))
      assertFalse(index.isInContent(shellFile))
    }
    openToolWindow("Project")
    val tree = ideFrame().projectView().projectViewTree
    waitFor("actual Project tree matches revision ${fixture.revision}", 1.minutes) {
      tree.expandAll(10.seconds)
      val paths = tree.collectExpandedPaths().map { it.path }
      setOf("repo-a", "repo-b").all { repo -> paths.any { repo in it } == (repo in selected) } &&
        paths.none { path -> path.drop(1).any { it in setOf(".reqws", "reqws-project.json", "goland") } } &&
        paths.any { "user-content" in it && it.last() == "keep.txt" } &&
        selected.all { repo -> paths.any { it.takeLast(3) == listOf(repo, "docs", "probe.txt") } }
    }
    openToolWindow("ReqWS")
    val count = ideFrame().x { byVisibleText("Loaded repositories: ${selected.size}") }
    // UI text must agree too; model convergence alone is not a Project-panel/UI pass.
    waitFor("ReqWS loaded count", 30.seconds) { count.present() }
  }

  private fun digest() = MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(archive))
    .joinToString("") { "%02x".format(it.toInt() and 0xff) }

  private fun event(pid: Long, phase: String) {
    Files.writeString(root.resolve("processes.tsv"), "$pid\t$phase\n", StandardOpenOption.CREATE, StandardOpenOption.APPEND)
  }
}
