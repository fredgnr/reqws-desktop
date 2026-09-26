package com.reqws.goland

import com.fasterxml.jackson.databind.ObjectMapper
import com.intellij.driver.client.Driver
import com.intellij.driver.client.utility
import com.intellij.driver.client.service
import com.intellij.driver.sdk.ProjectRootManager
import com.intellij.driver.sdk.ModuleRootManager
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
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

internal class IdeScenarioHost {
  val root = Path.of(System.getProperty("reqws.integration.root")).toRealPath()
  private val archive = Path.of(System.getProperty("reqws.plugin.archive"))
  private val environment = LocalIdeEnvironment()
  private val processes = mutableSetOf<Long>()
  private val candidateDigest = requireNotNull(System.getProperty("reqws.plugin.expectedSha256"))
  private val untrustedContexts = mutableSetOf<IDETestContext>()
  private val json = ObjectMapper()

  fun prepare() {
    environment.prepareHost()
    check(digest() == candidateDigest) { "Candidate changed before the host started" }
  }

  fun context(name: String, project: Path, preTrustProject: Boolean = true): IDETestContext = Starter.newContext(
    "reqws-$name", environment.testCase(LocalProjectInfo(project, isReusable = true)),
    preserveSystemDir = true,
  ).apply {
    environment.configure(this, project, preTrustProject)
    if (!preTrustProject) untrustedContexts += this
    pluginConfigurator.installPluginFromPath(archive).assertPluginIsInstalled("com.reqws.workspace")
  }

  fun withIde(context: IDETestContext, beforeProjectOpen: Driver.() -> Unit = {}, block: Driver.() -> Unit) {
    check(digest() == candidateDigest) { "Candidate changed before launch" }
    environment.recordLaunchRequested()
    val run = try {
      context.runIdeWithDriver(runTimeout = 10.minutes) { environment.configureRun(this, context in untrustedContexts) }
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
        beforeProjectOpen()
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

  fun assertProjection(driver: Driver, fixture: ProjectionFixture, selected: Set<String> = fixture.selected) = with(driver) {
    fixture.verifyInputs()
    assertEquals(fixture.selected, selected)
    val expectedDigest = fixture.expectedProjectionDigest()
    val expectedIds = selected.map { fixture.repositories.getValue(it) }.toSet()
    val reqws = service<ReqwsRemoteService>(singleProject())
    waitFor("revision ${fixture.revision} applied with live projection proof", 2.minutes) {
      val state = reqws.getState()
      val loading = state.getSnapshot()?.getLoading()
      loading != null && state.getLifecycle().name() in setOf("SYNCHRONIZED", "DEGRADED") &&
        loading.getProject().getRevision() == fixture.revision &&
        loading.getProject().getWorkspaceId() == fixture.workspaceId &&
        loading.getProject().getBindingId() == fixture.bindingId &&
        loading.getLoadedIds().toSet() == expectedIds && loading.getDigest() == expectedDigest &&
        state.getValidatedProjectionDigest() == loading.getDigest() &&
        state.getLastAppliedDigest() == loading.getDigest()
    }
    val model = modelEvidence(this, fixture)
    val roots = model.roots.toSet()
    for (name in setOf("repo-a", "repo-b")) {
      assertEquals(name in selected, fixture.root.resolve(name).toString() in roots)
      for (path in listOf(fixture.root.resolve(name), fixture.root.resolve("$name/docs/probe.txt"))) {
        assertEquals(name in selected, model.pfi.getValue(path.toString()).getValue("inContent"))
      }
    }
    assertEquals(fixture.hasUserModel, fixture.root.resolve("user-content").toString() in roots)
    assertEquals(fixture.hasUserModel, model.pfi.getValue(fixture.root.resolve("user-content/keep.txt").toString()).getValue("inContent"))
    assertEquals(selected.map { fixture.root.resolve(it).toString() }.toSet(),
      model.modules["ReqWS-${fixture.bindingId}"].orEmpty().toSet(), "ReqWS module roots differ from the Desktop selection")
    if (fixture.hasUserModel) {
      assertEquals(listOf(fixture.root.resolve("user-content").toString()), model.modules["user"])
    }
    val allowedRoots = fixture.repositories.keys.map { fixture.root.resolve(it).toString() }.toSet() +
      setOf(fixture.shell.toString(), fixture.root.resolve("user-content").toString())
    assertTrue(roots.all { it in allowedRoots }, "Project model contains an unexpected root")
    withReadAction {
      val index = service<RemoteProjectFileIndex>(singleProject())
      val files = utility<RemoteLocalFileSystem>().getInstance()
      for (name in setOf("repo-a", "repo-b", "user-content")) {
        val file = requireNotNull(files.findFileByPath(fixture.root.resolve(name).toString()))
        assertEquals(name in selected || name == "user-content" && fixture.hasUserModel, index.isInContent(file))
      }
      val shellFile = requireNotNull(files.findFileByPath(fixture.shell.toString()))
      assertTrue(index.isExcluded(shellFile))
      assertFalse(index.isInContent(shellFile))
    }
    var displayedPaths = emptyList<List<String>>()
    waitFor("actual Project tree matches revision ${fixture.revision}", 1.minutes) {
      displayedPaths = projectTree(this, fixture, "projection")
      // The fixed IDE appends a module name and/or absolute location to content-root labels.
      // Match complete fixture names after removing only these presentation suffixes.
      val paths = displayedPaths.map { path -> path.map { it.substringBefore(" [").substringBefore(" /") } }
      setOf("repo-a", "repo-b").all { repo -> paths.any { repo in it } == (repo in selected) } &&
        paths.none { path -> path.any { it in setOf(".reqws", "reqws-project.json", "goland") } } &&
        (!fixture.hasUserModel || paths.any { "user-content" in it && it.last() == "keep.txt" }) &&
        selected.all { repo -> paths.any { it.takeLast(3) == listOf(repo, "docs", "probe.txt") } }
    }
    openToolWindow("ReqWS")
    val count = ideFrame().x { byVisibleText("Loaded repositories: ${selected.size}") }
    // UI text must agree too; model convergence alone is not a Project-panel/UI pass.
    waitFor("ReqWS loaded count", 30.seconds) { count.present() }
    fixture.verifyInputs()
    fixture.assertDiskPreserved()
    recordProof(this, fixture, "projection", model, displayedPaths)
  }

  fun modelEvidence(driver: Driver, fixture: ProjectionFixture): ModelEvidence = with(driver) {
    withReadAction {
      val roots = service<ProjectRootManager>(singleProject()).getContentRoots().map { it.getPath() }.sorted()
      val modules = getModules().associate { module ->
        module.getName() to utility<ModuleRootManager>().getInstance(module).getContentEntries()
          .map { it.getFile().getPath() }.sorted()
      }.toSortedMap()
      val index = service<RemoteProjectFileIndex>(singleProject())
      val files = utility<RemoteLocalFileSystem>().getInstance()
      val paths = fixture.repositories.keys.flatMap { listOf(fixture.root.resolve(it), fixture.root.resolve("$it/docs/probe.txt")) } +
        listOf(fixture.root.resolve("user-content"), fixture.root.resolve("user-content/keep.txt"), fixture.shell)
      val pfi = paths.associate { path ->
        val file = requireNotNull(files.findFileByPath(path.toString())) { "Fixture file is absent from VFS: ${path.fileName}" }
        path.toString() to mapOf("inContent" to index.isInContent(file), "excluded" to index.isExcluded(file))
      }.toSortedMap()
      ModelEvidence(roots, modules, pfi)
    }
  }

  fun projectTree(driver: Driver, fixture: ProjectionFixture, phase: String): List<List<String>> = with(driver) {
    openToolWindow("Project")
    val tree = ideFrame().projectView().projectViewTree
    tree.expandAll(10.seconds)
    tree.collectExpandedPaths().map { it.path }.also { paths ->
      val scenario = (fixture as? DesktopProjectionFixture)?.name ?: "legacy"
      Files.writeString(root.resolve("project-tree-${processes.last()}-$scenario-${fixture.revision}-$phase.txt"),
        paths.joinToString("\n") { it.joinToString(" > ") })
    }
  }

  fun recordProof(
    driver: Driver, fixture: ProjectionFixture, phase: String,
    model: ModelEvidence = modelEvidence(driver, fixture),
    tree: List<List<String>> = projectTree(driver, fixture, phase),
  ) {
    if (fixture !is DesktopProjectionFixture) return
    val state = driver.service<ReqwsRemoteService>(driver.singleProject()).getState()
    val loading = state.getSnapshot()?.getLoading()
    val proof = mapOf(
      "scenario" to fixture.name, "phase" to phase, "pid" to processes.last(), "revision" to fixture.revision,
      "selected" to fixture.selected.sorted(), "workspaceId" to fixture.workspaceId, "bindingId" to fixture.bindingId,
      "roots" to model.roots, "modules" to model.modules, "pfi" to model.pfi, "tree" to tree,
      "lifecycle" to state.getLifecycle().name(), "error" to state.getLastError()?.getCode(),
      "loadingDigest" to loading?.getDigest(), "validatedProjectionDigest" to state.getValidatedProjectionDigest(),
      "lastAppliedDigest" to state.getLastAppliedDigest(), "loadedIds" to loading?.getLoadedIds()?.toList(),
      "trusted" to driver.utility<RemoteTrustedProjects>().isProjectTrusted(driver.singleProject()),
    )
    Files.writeString(root.resolve("desktop-projections.jsonl"), json.writeValueAsString(proof) + "\n",
      StandardOpenOption.CREATE, StandardOpenOption.APPEND)
  }

  private fun digest() = sha256(Files.readAllBytes(archive))

  private fun event(pid: Long, phase: String) {
    Files.writeString(root.resolve("processes.tsv"), "$pid\t$phase\n", StandardOpenOption.CREATE, StandardOpenOption.APPEND)
  }
}

internal data class ModelEvidence(
  val roots: List<String>,
  val modules: Map<String, List<String>>,
  val pfi: Map<String, Map<String, Boolean>>,
)
