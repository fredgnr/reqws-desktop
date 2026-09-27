package com.reqws.goland

import com.intellij.driver.client.Driver
import com.intellij.driver.client.Remote
import com.intellij.driver.model.OnDispatcher
import com.intellij.driver.sdk.Project
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
class ComposeContentLifecycleTest {
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
  fun contentLifecycleIsIndependentOfProjectSynchronization() {
    val fixture = WorkspaceFixture(root)
    withIde(context("compose-content-lifecycle", fixture.shell)) {
      val reqws = service<ReqwsRemoteService>(singleProject())
      val manager = service<ComposeLifecycleToolWindowManager>(singleProject())
      val window = requireNotNull(manager.getToolWindow("ReqWS"))
      waitFor("projection without opening content", 2.minutes) {
        reqws.getState().getValidatedProjectionDigest() != null
      }
      withContext(OnDispatcher.EDT) {
        assertTrue(window.getContentManagerIfCreated()?.getContentCount() in listOf(null, 0))
      }
      fixture.select(listOf("repo-a"))
      waitFor("unopened panel does not gate automatic refresh", 2.minutes) {
        reqws.getState().getSnapshot()?.getLoading()?.getProject()?.getRevision() == fixture.revision &&
          reqws.getState().getValidatedProjectionDigest() != null
      }
      openToolWindow("ReqWS")
      val status = ideFrame().x { byAttribute("testtag", "reqws.status") }
      waitFor("production Compose content", 30.seconds) { status.present() }
      repeat(3) {
        val owner = withContext(OnDispatcher.EDT) {
          assertEquals(1, window.getContentManager().getContentCount())
          val owner = window.getContentManager().getContents().single().getDisposer()
          assertFalse(owner.isDisposed())
          // Repeated factory calls must not register a second Content or listener.
          new(ComposeLifecycleFactory::class).createToolWindowContent(singleProject(), window)
          assertEquals(1, window.getContentManager().getContentCount())
          window.hide(null)
          assertFalse(owner.isDisposed())
          owner
        }
        openToolWindow("ReqWS")
        waitFor("production composition resumes after hide", 10.seconds) { status.present() }
        withContext(OnDispatcher.EDT) {
          window.getContentManager().removeAllContents(true)
          assertTrue(owner.isDisposed())
          assertEquals(0, window.getContentManager().getContentCount())
        }
        fixture.select(if (it % 2 == 0) emptyList() else listOf("repo-a", "repo-b"))
        waitFor("project service survives Content disposal", 2.minutes) {
          reqws.getState().getSnapshot()?.getLoading()?.getProject()?.getRevision() == fixture.revision &&
            reqws.getState().getValidatedProjectionDigest() != null
        }
        withContext(OnDispatcher.EDT) {
          new(ComposeLifecycleFactory::class).createToolWindowContent(singleProject(), window)
          assertFalse(window.getContentManager().getContents().single().getDisposer().isDisposed())
        }
        openToolWindow("ReqWS")
        waitFor("recreated production composition", 10.seconds) { status.present() }
      }
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
      context.runIdeWithDriver(runTimeout = 10.minutes) {
        environment.configureRun(this)
        // The bundled periodic diagnostic paints windows into BufferedImage;
        // Metal shared textures cannot render into that offscreen configuration.
        // Keep IDE errors enabled and capture actual screen pixels in this test.
        addVMOptionsPatch { clearSystemProperty("ide.performance.screenshot") }
      }
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

  private fun digest() = MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(archive))
    .joinToString("") { "%02x".format(it.toInt() and 0xff) }

  private fun event(pid: Long, phase: String) {
    Files.writeString(root.resolve("processes.tsv"), "$pid\t$phase\n", StandardOpenOption.CREATE, StandardOpenOption.APPEND)
  }
}

@Remote("com.intellij.openapi.wm.ToolWindowManager")
interface ComposeLifecycleToolWindowManager { fun getToolWindow(id: String): ComposeLifecycleToolWindow? }
@Remote("com.intellij.openapi.wm.ToolWindow")
interface ComposeLifecycleToolWindow {
  fun isVisible(): Boolean
  fun getContentManagerIfCreated(): ComposeLifecycleContentManager?
  fun getContentManager(): ComposeLifecycleContentManager
  fun hide(runnable: Runnable?)
}
@Remote("com.intellij.ui.content.ContentManager")
interface ComposeLifecycleContentManager {
  fun getContentCount(): Int
  fun removeAllContents(dispose: Boolean)
  fun getContents(): Array<ComposeLifecycleContent>
}
@Remote("com.reqws.goland.ui.ReqwsToolWindowFactory", plugin = "com.reqws.workspace")
interface ComposeLifecycleFactory { fun createToolWindowContent(project: Project, window: ComposeLifecycleToolWindow) }


@Remote("com.intellij.ui.content.Content")
interface ComposeLifecycleContent { fun getDisposer(): ComposeLifecycleOwner }
@Remote("com.reqws.goland.ui.platform.ReqwsContentSession", plugin = "com.reqws.workspace")
interface ComposeLifecycleOwner { fun isDisposed(): Boolean }
