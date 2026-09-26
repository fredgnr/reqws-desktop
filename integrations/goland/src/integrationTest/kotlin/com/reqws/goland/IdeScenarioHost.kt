package com.reqws.goland

import com.fasterxml.jackson.databind.ObjectMapper
import com.intellij.driver.client.Driver
import com.intellij.driver.client.utility
import com.intellij.driver.client.service
import com.intellij.driver.sdk.ProjectRootManager
import com.intellij.driver.sdk.ModuleRootManager
import com.intellij.driver.sdk.getModules
import com.intellij.driver.sdk.getPlugin
import com.intellij.driver.sdk.getOpenProjects
import com.intellij.driver.sdk.invokeAction
import com.intellij.driver.sdk.isPluginLoaded
import com.intellij.driver.sdk.openToolWindow
import com.intellij.driver.sdk.singleProject
import com.intellij.driver.sdk.ui.components.common.ideFrame
import com.intellij.driver.sdk.ui.components.common.welcomeScreen
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
    check(digest() == candidateDigest) { "Candidate changed before plugin installation" }
    environment.configure(this, project, preTrustProject)
    if (!preTrustProject) untrustedContexts += this
    pluginConfigurator.installPluginFromPath(archive).assertPluginIsInstalled("com.reqws.workspace")
    check(digest() == candidateDigest) { "Installer changed the candidate bytes" }
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

  fun assertProjection(driver: Driver, fixture: ProjectionFixture, selected: Set<String> = fixture.selected,
    phase: String = "projection") = with(driver) {
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
    // A real external late file must reach VFS on its own; never force refresh.
    if (fixture.hasLateFiles) waitFor("late fixture files observed by the native watcher", 1.minutes) {
      val files = utility<RemoteLocalFileSystem>().getInstance()
      files.findFileByPath(fixture.root.resolve("repo-a/docs/late-repo.txt").toString()) != null &&
        files.findFileByPath(fixture.shell.resolve("late-shell.txt").toString()) != null
    }
    val model = modelEvidence(this, fixture)
    val roots = model.roots.toSet()
    for (name in fixture.repositories.keys) {
      val included = name in selected || name in fixture.userCoveredRepositories
      assertEquals(included, fixture.root.resolve(name).toString() in roots)
      for (path in listOf(fixture.root.resolve(name), fixture.root.resolve("$name/docs/probe.txt"))) {
        assertEquals(included, model.pfi.getValue(path.toString()).getValue("inContent"))
      }
    }
    assertEquals(fixture.hasUserModel, fixture.root.resolve("user-content").toString() in roots)
    assertEquals(fixture.hasUserModel, model.pfi.getValue(fixture.root.resolve("user-content/keep.txt").toString()).getValue("inContent"))
    assertEquals(selected.map { fixture.root.resolve(it).toString() }.toSet() +
      if (fixture.hasManagedUserRoot) setOf(fixture.root.resolve("user-extra").toString()) else emptySet(),
      model.modules["ReqWS-${fixture.bindingId}"].orEmpty().toSet(), "ReqWS module roots differ from the Desktop selection")
    if (fixture.hasUserModel) {
      assertEquals((listOf(fixture.root.resolve("user-content").toString()) +
        fixture.userCoveredRepositories.map { fixture.root.resolve(it).toString() }).sorted(), model.modules["user"])
    }
    val allowedRoots = fixture.repositories.keys.map { fixture.root.resolve(it).toString() }.toSet() +
      setOf(fixture.shell.toString(), fixture.root.resolve("user-content").toString()) +
      if (fixture.hasManagedUserRoot) setOf(fixture.root.resolve("user-extra").toString()) else emptySet()
    assertTrue(roots.all { it in allowedRoots }, "Project model contains an unexpected root")
    withReadAction {
      val index = service<RemoteProjectFileIndex>(singleProject())
      val files = utility<RemoteLocalFileSystem>().getInstance()
      for (name in fixture.repositories.keys + "user-content") {
        val file = requireNotNull(files.findFileByPath(fixture.root.resolve(name).toString()))
        assertEquals(name in selected || name in fixture.userCoveredRepositories || name == "user-content" && fixture.hasUserModel, index.isInContent(file))
      }
      val shellFile = requireNotNull(files.findFileByPath(fixture.shell.toString()))
      assertTrue(index.isExcluded(shellFile))
      assertFalse(index.isInContent(shellFile))
    }
    if (fixture is DesktopProjectionFixture) {
      for (relative in listOf("notes/outside.txt", ".reqws/ide/goland/shell-probe.txt")) {
        assertFalse(model.pfi.getValue(fixture.root.resolve(relative).toString()).getValue("inContent"))
      }
      assertEquals(fixture.hasManagedUserRoot, model.pfi.getValue(fixture.root.resolve("user-extra/keep-extra.txt").toString()).getValue("inContent"))
      assertEquals(fixture.userCoveredRepositories.map { fixture.repositories.getValue(it) }.toSet(), reqws.getState().getUserRootCoverage())
      if (fixture.hasLateFiles) {
        assertEquals("repo-a" in selected, model.pfi.getValue(fixture.root.resolve("repo-a/docs/late-repo.txt").toString()).getValue("inContent"))
        assertEquals(mapOf("inContent" to false, "excluded" to true), model.pfi.getValue(fixture.shell.resolve("late-shell.txt").toString()))
      }
    }
    var displayedPaths = emptyList<List<String>>()
    waitFor("actual Project tree matches revision ${fixture.revision}", 1.minutes) {
      displayedPaths = projectTree(this, fixture, phase)
      // The fixed IDE appends a module name and/or absolute location to content-root labels.
      // Match complete fixture names after removing only these presentation suffixes.
      val paths = displayedPaths.map { path -> path.map { it.substringBefore(" [").substringBefore(" /") } }
      fixture.repositories.keys.all { repo -> paths.any { repo in it } == (repo in selected || repo in fixture.userCoveredRepositories) } &&
        paths.none { path -> path.any { it in setOf(".reqws", "reqws-project.json", "goland", "shell-probe.txt", "late-shell.txt", "notes", "outside.txt") } } &&
        (!fixture.hasUserModel || paths.any { "user-content" in it && it.last() == "keep.txt" }) &&
        (!fixture.hasManagedUserRoot || paths.any { "user-extra" in it && it.last() == "keep-extra.txt" }) &&
        (!fixture.hasLateFiles || paths.any { it.takeLast(3) == listOf("repo-a", "docs", "late-repo.txt") } == ("repo-a" in selected)) &&
        (selected + fixture.userCoveredRepositories).all { repo -> paths.any { it.takeLast(3) == listOf(repo, "docs", "probe.txt") } }
    }
    openToolWindow("ReqWS")
    val count = ideFrame().x { byVisibleText("Loaded repositories: ${selected.size}") }
    // UI text must agree too; model convergence alone is not a Project-panel/UI pass.
    waitFor("ReqWS loaded count", 30.seconds) { count.present() }
    if (fixture.userCoveredRepositories.isNotEmpty()) {
      waitFor("ReqWS explains the visible unselected user root", 30.seconds) {
        ideFrame().x { byVisibleText("Included via User Project Root") }.present()
      }
    }
    fixture.verifyInputs()
    fixture.assertDiskPreserved()
    recordProof(this, fixture, phase, model, displayedPaths)
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
        listOf(fixture.root.resolve("user-content"), fixture.root.resolve("user-content/keep.txt"), fixture.shell) +
        if (fixture is DesktopProjectionFixture) listOf(fixture.root.resolve("user-extra/keep-extra.txt"), fixture.root.resolve("notes/outside.txt"),
          fixture.shell.resolve("shell-probe.txt")) + if (fixture.hasLateFiles) listOf(fixture.root.resolve("repo-a/docs/late-repo.txt"), fixture.shell.resolve("late-shell.txt")) else emptyList()
        else emptyList()
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
      "userCoverage" to state.getUserRootCoverage().sorted(),
      "managedUserRoot" to fixture.hasManagedUserRoot, "lateFiles" to fixture.hasLateFiles,
      "showExcludedFiles" to excludedFiles(driver), "vcsMappings" to vcsMappings(driver),
      "screenshot" to screenshot(driver, "${fixture.name}-${fixture.revision}-$phase"),
    )
    Files.writeString(root.resolve("desktop-projections.jsonl"), json.writeValueAsString(proof) + "\n",
      StandardOpenOption.CREATE, StandardOpenOption.APPEND)
  }

  fun excludedFiles(driver: Driver): Boolean = with(driver) {
    val view = utility<RemoteProjectView>().getInstance(singleProject())
    check(view.getCurrentViewId() == "ProjectPane") { "Assertions require the ordinary Project view" }
    view.isShowExcludedFiles("ProjectPane")
  }

  fun setExcludedFiles(driver: Driver, enabled: Boolean) = with(driver) {
    openToolWindow("Project")
    if (excludedFiles(this) != enabled) {
      val tree = ideFrame().projectView().projectViewTree
      tree.setFocus()
      invokeAction("ProjectView.ShowExcludedFiles", component = tree.component)
      waitFor("Excluded Files user action applied", 30.seconds) { excludedFiles(this) == enabled }
    }
  }

  fun reopenProject(driver: Driver, fixture: ProjectionFixture) = with(driver) {
    val name = singleProject().getName()
    assertEquals(fixture.shell.toString(), singleProject().getBasePath())
    invokeAction("CloseProject", false)
    waitFor("project closed without terminating the IDE process", 1.minutes) { getOpenProjects().isEmpty() }
    welcomeScreen().clickRecentProject(name)
    waitFor("same fixture reopened in the existing IDE process", 2.minutes) {
      getOpenProjects().singleOrNull()?.getBasePath() == fixture.shell.toString()
    }
  }

  fun vcsMappings(driver: Driver): List<Map<String, String>> = with(driver) {
    utility<RemoteVcsManager>().getInstance(singleProject()).getDirectoryMappings()
      .map { mapOf("directory" to it.getDirectory(), "vcs" to it.getVcs()) }.sortedBy { it.getValue("directory") }
  }

  fun recordOrdinary(driver: Driver, project: Path) = with(driver) {
    val shell = project.resolve(".reqws/ide/goland")
    waitFor("unbound same-name shell is inactive", 1.minutes) {
      service<ReqwsRemoteService>(singleProject()).getState().getLifecycle().name() == "INACTIVE"
    }
    assertEquals(project.toString(), singleProject().getBasePath())
    assertFalse(getModules().any { it.getName().startsWith("reqws-", ignoreCase = true) })
    openToolWindow("Project")
    var tree = emptyList<List<String>>()
    waitFor("ordinary same-name shell and its real file remain visible", 1.minutes) {
      val component = ideFrame().projectView().projectViewTree
      component.expandAll(10.seconds)
      tree = component.collectExpandedPaths().map { it.path }
      tree.any { it.takeLast(4) == listOf(".reqws", "ide", "goland", "shell-probe.txt") }
    }
    val file = requireNotNull(utility<RemoteLocalFileSystem>().getInstance().findFileByPath(shell.resolve("shell-probe.txt").toString()))
    val inContent = service<RemoteProjectFileIndex>(singleProject()).isInContent(file)
    assertTrue(inContent)
    check(Files.readString(shell.resolve("shell-probe.txt")) == "unbound same-name shell\n")
    assertFalse(Files.exists(shell.resolve("reqws-project.json")))
    assertFalse(Files.exists(shell.resolve(".idea/reqws-loaded-roots.json")))
    assertFalse(Files.exists(project.resolve(".reqws/workspace.json")))
    Files.writeString(root.resolve("desktop-ordinary.json"), json.writeValueAsString(mapOf(
      "pid" to processes.last(), "project" to project.toString(), "lifecycle" to "INACTIVE",
      "tree" to tree, "inContent" to inContent, "vcsMappings" to vcsMappings(this),
      "screenshot" to screenshot(this, "ordinary-shell"),
    )))
  }

  private fun screenshot(driver: Driver, name: String): String = with(driver) {
    openToolWindow("Project")
    ideFrame().projectView().projectViewTree.setFocus()
    // Public Driver screenshot API; the fixed SDK returns a PNG under its log directory.
    val file = Path.of(requireNotNull(takeScreenshot("reqws-${processes.last()}-$name")))
    check(file.isAbsolute && file.startsWith(root) && file.toRealPath() == file && Files.isRegularFile(file))
    val image = requireNotNull(javax.imageio.ImageIO.read(file.toFile())) { "IDE screenshot is not readable" }
    check(image.width > 0 && image.height > 0)
    file.toString()
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
