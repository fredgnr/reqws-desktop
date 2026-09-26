package com.reqws.goland

import com.fasterxml.jackson.databind.ObjectMapper
import com.intellij.driver.client.Driver
import com.intellij.driver.client.Remote
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
import com.intellij.driver.sdk.ui.components.go.goWelcomeScreen
import com.intellij.driver.sdk.ui.components.elements.tree
import com.intellij.driver.sdk.ui.components.common.toolwindows.projectView
import com.intellij.driver.sdk.waitFor
import com.intellij.driver.sdk.isProjectOpened
import com.intellij.driver.sdk.ui.components.common.dialogs.licenseDialog
import com.intellij.driver.model.OnDispatcher
import com.intellij.driver.model.LockSemantics
import com.intellij.ide.starter.driver.engine.runIdeWithDriver
import com.intellij.ide.starter.ide.IDETestContext
import com.intellij.ide.starter.project.LocalProjectInfo
import com.intellij.ide.starter.runner.Starter
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.util.UUID
import javax.xml.parsers.DocumentBuilderFactory
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
  private var lateDiagnosticChanges = 0

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
      val result = run.useDriverAndCloseIde(1.minutes, false) {
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
    if (fixture.hasLateFiles) waitForLateFiles(this, fixture, phase)
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
      val repositories = ideFrame().x { byJavaClass("com.reqws.goland.ui.ReqwsToolWindowPanel") }
        .x { byJavaClass("com.reqws.goland.ui.ReqwsRepositoryList") }
      waitFor("ReqWS explains the visible unselected user root", 30.seconds) {
        if (!repositories.present()) false else {
          val texts = repositories.getAllTexts().map { it.text }
          "Included via User Project Root" in texts && fixture.userCoveredRepositories.all { it in texts }
        }
      }
    }
    fixture.verifyInputs()
    fixture.assertDiskPreserved()
    recordProof(this, fixture, phase, model, displayedPaths)
  }

  private fun waitForLateFiles(driver: Driver, fixture: ProjectionFixture, phase: String) = with(driver) {
    val repoPath = fixture.root.resolve("repo-a/docs/late-repo.txt")
    val shellPath = fixture.shell.resolve("late-shell.txt")
    val ide = ideFrame()
    check(requireNotNull(ide.project).getBasePath() == fixture.shell.toString())
    val frame = cast(ide.component, RemoteCaptureFrame::class)
    val files = utility<RemoteLocalFileSystem>().getInstance()
    val started = System.nanoTime()
    var last = emptyMap<String, Boolean>()
    var recorded = emptyMap<String, Boolean>()
    fun record(event: String) {
      Files.writeString(root.resolve("late-vfs-observations.jsonl"), json.writeValueAsString(mapOf(
        "event" to event, "pid" to processes.last(), "phase" to phase, "revision" to fixture.revision,
        "elapsedMillis" to (System.nanoTime() - started) / 1_000_000,
        "repoPath" to repoPath.toString(), "shellPath" to shellPath.toString(), "observation" to last,
      )) + "\n", StandardOpenOption.CREATE, StandardOpenOption.APPEND)
    }
    try {
      waitFor("late fixture files observed by the native watcher", 1.minutes) {
        // Query both independently so a missing repository file cannot conceal
        // whether the excluded shell was discovered. These calls never refresh.
        val repoVfs = files.findFileByPath(repoPath.toString()) != null
        val shellVfs = files.findFileByPath(shellPath.toString()) != null
        last = mapOf("repoDiskExists" to Files.exists(repoPath), "shellDiskExists" to Files.exists(shellPath),
          "repoVfsExists" to repoVfs, "shellVfsExists" to shellVfs,
          "frameFocused" to frame.isFocused(), "frameActive" to frame.isActive())
        if (last != recorded && lateDiagnosticChanges < 32) {
          record("state-change")
          recorded = last
          lateDiagnosticChanges++
        }
        repoVfs && shellVfs
      }
      record("wait-completed")
    } catch (failure: Exception) {
      val diagnostic = IllegalStateException("Late-file VFS wait failed: repo=$repoPath, shell=$shellPath, last=$last", failure)
      try { record("wait-failed") } catch (recordFailure: Exception) { diagnostic.addSuppressed(recordFailure) }
      throw diagnostic
    }
  }

  fun createLateFilesThroughExternalEdit(driver: Driver, fixture: DesktopProjectionFixture, desktop: DesktopLink) = with(driver) {
    require(fixture.name == "selection" && fixture.revision == 1L && !fixture.hasLateFiles)
    val previous = json.readTree(Files.readAllLines(root.resolve("desktop-projections.jsonl")).last())
    check(previous.path("scenario").asText() == "selection" && previous.path("phase").asText() == "excluded-on" &&
      previous.path("pid").asLong() == processes.last() && previous.path("revision").asLong() == 1L)
    val ide = ideFrame()
    check(requireNotNull(ide.project).getBasePath() == fixture.shell.toString())
    val frame = cast(ide.component, RemoteCaptureFrame::class)
    val focus = desktop.focusForExternalEdit(fixture)
    check(focus.desktopPid != processes.last())
    waitFor("owned Desktop window has deactivated the test IDE", 30.seconds) {
      desktop.checkAbort()
      !frame.isFocused() && !frame.isActive()
    }
    val stages = mutableListOf<Map<String, Any>>()
    fun observe(stage: String, active: Boolean, filesExist: Boolean) {
      val value = mapOf("stage" to stage, "frameFocused" to frame.isFocused(), "frameActive" to frame.isActive(),
        "repoDiskExists" to Files.exists(fixture.root.resolve("repo-a/docs/late-repo.txt")),
        "shellDiskExists" to Files.exists(fixture.shell.resolve("late-shell.txt")))
      check(value["frameFocused"] == active && value["frameActive"] == active &&
        value["repoDiskExists"] == filesExist && value["shellDiskExists"] == filesExist) { "External edit focus/file boundary changed" }
      stages += value
    }
    observe("desktop-focused", active = false, filesExist = false)
    fixture.createLateFiles()
    observe("files-created", active = false, filesExist = true)
    // Only G3 models returning from an external file edit. G2 selection changes
    // never use activation or refresh as a substitute for the manifest watcher.
    ide.ensureFocused()
    waitFor("returned to the same test IDE after the external file edit", 30.seconds) {
      desktop.checkAbort()
      frame.isFocused() && frame.isActive()
    }
    check(requireNotNull(ide.project).getBasePath() == fixture.shell.toString())
    observe("ide-returned", active = true, filesExist = true)
    Files.writeString(root.resolve("desktop-external-edit.json"), json.writeValueAsString(mapOf(
      "schemaVersion" to 1, "sessionId" to System.getProperty("reqws.desktop.session"),
      "scenario" to fixture.name, "phase" to "late-files-on", "revision" to fixture.revision,
      "workspaceId" to fixture.workspaceId, "bindingId" to fixture.bindingId, "project" to fixture.shell.toString(),
      "idePid" to processes.last(), "desktopPid" to focus.desktopPid, "windowId" to focus.windowId,
      "requestSequence" to focus.requestSequence, "stages" to stages,
    )), StandardOpenOption.CREATE_NEW)
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

  fun reopenProject(driver: Driver, fixture: DesktopProjectionFixture) = with(driver) {
    require(fixture.name == "selection" && fixture.revision == 7L && fixture.selected.isEmpty())
    val project = fixture.shell.toRealPath().toString()
    val original = singleProject()
    assertEquals(project, original.getBasePath())
    assertTrue(original.isOpen())
    val pid = utility<RemoteCaptureManagementFactory>().getRuntimeMXBean().getPid()
    check(pid == processes.last())
    val previous = json.readTree(Files.readAllLines(root.resolve("desktop-projections.jsonl")).last())
    check(previous.path("scenario").asText() == fixture.name && previous.path("phase").asText() == "user-root-empty" &&
      previous.path("revision").asLong() == fixture.revision && previous.path("pid").asLong() == pid &&
      previous.path("bindingId").asText() == fixture.bindingId)
    val config = Path.of(requireNotNull(utility<LocalIdeSystemProperties>().getProperty("idea.config.path"))).toRealPath()
    check(config == Path.of(System.getProperty("reqws.local.profile")).resolve("config").toRealPath())
    val welcomeProject = config.resolve("projects/GoLandWorkspace").toString()
    val stages = mutableListOf<Map<String, Any?>>()
    fun record(stage: Map<String, Any?>) {
      check(utility<RemoteCaptureManagementFactory>().getRuntimeMXBean().getPid() == pid)
      stages += stage + ("pid" to pid)
      Files.writeString(root.resolve("desktop-project-reopen-diagnostic.json"), json.writeValueAsString(mapOf(
        "project" to project, "stages" to stages,
      )))
    }
    invokeAction("CloseProject", false)
    // 262 keeps a real welcome workspace open after closing the fixture project.
    // Only that exact dedicated-profile workspace may remain in this process.
    waitFor("fixture closed without terminating the IDE process", 1.minutes) {
      !original.isOpen() && getOpenProjects().map { it.getBasePath() }.all { it == welcomeProject }
    }
    record(mapOf("stage" to "closed", "originalOpen" to original.isOpen(),
      "openProjects" to getOpenProjects().map { it.getBasePath() }))
    val welcome = goWelcomeScreen()
    val recent = welcome.tree("//div[@accessiblename='Recent Projects']")
    val paths = recent.collectExpandedPaths()
    val texts = recent.getAllTexts().map { it.text }
    record(mapOf("stage" to "recent-projects-observed", "tree" to paths.map {
      mapOf("row" to it.row, "path" to it.path)
    }, "visibleTexts" to texts))
    // 262's cell reader can return an empty path for this renderer. Bind the
    // actual single UI row to the complete, isolated recent-project record.
    val source = config.resolve("options/recentProjects.xml")
    check(Files.isRegularFile(source, LinkOption.NOFOLLOW_LINKS) && source.toRealPath() == source && Files.size(source) <= 65536)
    val bytes = Files.readAllBytes(source)
    val evidence = root.resolve("desktop-recent-projects.xml")
    Files.write(evidence, bytes, StandardOpenOption.CREATE_NEW)
    val (recentPaths, displayName) = readRecentProjects(bytes, project)
    check(paths.size == 1 && paths.single().row >= 0 && texts.count { it == displayName } == 1) {
      "Recent Projects must contain one visible row matching the unique recorded fixture: paths=$paths, texts=$texts"
    }
    val selected = paths.single()
    check(recent.collectExpandedPaths().map { it.row to it.path } == paths.map { it.row to it.path }) {
      "Recent Projects changed before the bound UI action"
    }
    check(recent.getAllTexts().map { it.text } == texts &&
      readRecentProjects(Files.readAllBytes(source), project) == (recentPaths to displayName)) {
      "Recent Projects identity changed before the bound UI action"
    }
    check(getOpenProjects().map { it.getBasePath() } == listOf(welcomeProject))
    val title = cast(welcome.component, RemoteCaptureFrame::class).getTitle()
    check(title == "GoLandWorkspace – Welcome to GoLand")
    // The diagnostic observation becomes the selected stage only after all
    // identity checks; the final record is emitted only after actual reopening.
    stages.removeAt(stages.lastIndex)
    record(mapOf("stage" to "recent-project-selected", "welcomeProject" to welcomeProject,
      "frameTitle" to title, "tree" to paths.map { mapOf("row" to it.row, "path" to it.path) },
      "selectedRow" to selected.row, "selectedPath" to selected.path, "visibleTexts" to texts,
      "recentProjectsFile" to evidence.toString(), "recentProjectPaths" to recentPaths, "displayName" to displayName))
    recent.doubleClickRow(selected.row)
    waitFor("same fixture reopened in the existing IDE process", 2.minutes) {
      getOpenProjects().singleOrNull()?.let { it.getBasePath() == project && it.isOpen() && it.isInitialized() } == true
    }
    assertFalse(original.isOpen())
    record(mapOf("stage" to "reopened", "originalOpen" to original.isOpen(), "projectOpen" to singleProject().isOpen(),
      "projectInitialized" to singleProject().isInitialized(), "openProjects" to getOpenProjects().map { it.getBasePath() }))
    Files.writeString(root.resolve("desktop-project-reopen.json"), json.writeValueAsString(mapOf(
      "schemaVersion" to 1, "sessionId" to System.getProperty("reqws.desktop.session"),
      "scenario" to fixture.name, "phase" to "reopened-empty", "revision" to fixture.revision,
      "workspaceId" to fixture.workspaceId, "bindingId" to fixture.bindingId, "project" to project,
      "idePid" to pid, "profileConfig" to config.toString(), "previousScreenshot" to previous.path("screenshot").asText(),
      "stages" to stages,
    )), StandardOpenOption.CREATE_NEW)
  }

  private fun readRecentProjects(bytes: ByteArray, project: String): Pair<List<String>, String> {
    val factory = DocumentBuilderFactory.newInstance().apply {
      setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
      setFeature("http://xml.org/sax/features/external-general-entities", false)
      setFeature("http://xml.org/sax/features/external-parameter-entities", false)
    }
    fun org.w3c.dom.Element.elements(): List<org.w3c.dom.Element> = (0 until childNodes.length)
      .mapNotNull { childNodes.item(it) as? org.w3c.dom.Element }
    val document = factory.newDocumentBuilder().parse(bytes.inputStream()).documentElement
    check(document.tagName == "application")
    val component = document.elements().single()
    check(component.tagName == "component" && component.getAttribute("name") == "RecentProjectsManager")
    val options = component.elements()
    check(options.all { it.tagName == "option" && it.getAttribute("name") in setOf("additionalInfo", "lastOpenedProject") })
    check(options.map { it.getAttribute("name") }.distinct().size == options.size)
    val welcomeKey = "\$APPLICATION_CONFIG_DIR\$/projects/GoLandWorkspace"
    options.singleOrNull { it.getAttribute("name") == "lastOpenedProject" }?.let {
      check(it.getAttribute("value") in setOf(project, welcomeKey))
    }
    val map = options.single { it.getAttribute("name") == "additionalInfo" }.elements().single()
    check(map.tagName == "map")
    val entries = map.elements()
    val keys = entries.map { it.getAttribute("key") }
    check(keys.distinct().size == keys.size && keys.count { it == project } == 1 && keys.all { it in setOf(project, welcomeKey) })
    var displayName = ""
    for (entry in entries) {
      check(entry.tagName == "entry")
      val value = entry.elements().single()
      check(value.tagName == "value")
      val metadata = value.elements().single()
      check(metadata.tagName == "RecentProjectMetaInfo")
      if (entry.getAttribute("key") == project) {
        check(metadata.getAttribute("hidden") in setOf("", "false"))
        displayName = metadata.getAttribute("displayName")
        check(displayName.isNotBlank())
      } else check(metadata.getAttribute("hidden") == "true")
    }
    return keys to displayName
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
    val inContent = withReadAction {
      val file = requireNotNull(utility<RemoteLocalFileSystem>().getInstance().findFileByPath(shell.resolve("shell-probe.txt").toString()))
      service<RemoteProjectFileIndex>(singleProject()).isInContent(file)
    }
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
    captureIdeContent(this, name)
  }

  fun captureIdeContent(driver: Driver, name: String): String = with(driver) {
    require(name.matches(Regex("[a-z0-9-]+")))
    val frame = ideFrame()
    val project = Path.of(requireNotNull(singleProject().getBasePath()))
    check(project.isAbsolute && project.startsWith(root) && project.toRealPath() == project)
    check(requireNotNull(frame.project).getBasePath() == project.toString()) { "The captured frame belongs to a different project" }
    val actualPid = utility<RemoteCaptureManagementFactory>().getRuntimeMXBean().getPid()
    check(actualPid == processes.last()) { "Capture is connected to a different IDE process" }
    val nativeFrame = cast(frame.component, RemoteCaptureFrame::class)
    val frameTitle = nativeFrame.getTitle()
    check(frameTitle.isNotBlank() && frameTitle.length <= 4096)
    val directory = Files.createDirectories(root.resolve("ide-component-captures"))
    check(directory.toRealPath() == directory)
    val file = Files.createFile(directory.resolve("${processes.last()}-$name-${UUID.randomUUID()}.png"))
    // Render the actual test IDE Swing root pane, not the display underneath it.
    // All invoked methods are public Driver or standard JDK APIs. No screen
    // capture, focus requirement, cropping or reconstructed UI is involved.
    val captured = withContext(OnDispatcher.EDT, LockSemantics.NO_LOCK) {
      val pane = nativeFrame.getRootPane()
      check(pane.isShowing()) { "The test IDE root pane is not showing" }
      val width = pane.getWidth()
      val height = pane.getHeight()
      check(width in 320..32768 && height in 200..32768 && width.toLong() * height <= 64L * 1024 * 1024)
      val image = new(RemoteCaptureImage::class, width, height, java.awt.image.BufferedImage.TYPE_INT_ARGB)
      val graphics = image.createGraphics()
      try { pane.printAll(graphics) } finally { graphics.dispose() }
      check(pane.getWidth() == width && pane.getHeight() == height) { "IDE content resized while being captured" }
      Triple(image, width, height)
    }
    check(utility<RemoteCaptureImageIO>().write(captured.first, "png", new(RemoteCaptureFile::class, file.toString())))
    check(file.toRealPath() == file && Files.isRegularFile(file) && Files.size(file) in 33..50L * 1024 * 1024)
    val decoded = requireNotNull(javax.imageio.ImageIO.read(file.toFile())) { "IDE component PNG is not readable" }
    check(decoded.width == captured.second && decoded.height == captured.third)
    check(requireNotNull(frame.project).getBasePath() == project.toString()) { "The captured project changed" }
    Files.writeString(file.resolveSibling("${file.fileName}.json"), json.writeValueAsString(mapOf(
      "schemaVersion" to 1, "captureKind" to "swing-root-pane-print-all", "project" to project.toString(), "pid" to actualPid,
      "frameProject" to requireNotNull(frame.project).getBasePath(), "frameTitle" to frameTitle,
      "width" to decoded.width, "height" to decoded.height,
    )), StandardOpenOption.CREATE_NEW)
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

@Remote("javax.swing.JFrame")
internal interface RemoteCaptureFrame {
  fun getRootPane(): RemoteCapturePane
  fun getTitle(): String
  fun isFocused(): Boolean
  fun isActive(): Boolean
}

@Remote("java.lang.management.ManagementFactory")
internal interface RemoteCaptureManagementFactory { fun getRuntimeMXBean(): RemoteCaptureRuntime }

@Remote("java.lang.management.RuntimeMXBean")
internal interface RemoteCaptureRuntime { fun getPid(): Long }

@Remote("javax.swing.JComponent")
internal interface RemoteCapturePane {
  fun getWidth(): Int
  fun getHeight(): Int
  fun isShowing(): Boolean
  fun printAll(graphics: RemoteCaptureGraphics)
}

@Remote("java.awt.Graphics")
internal interface RemoteCaptureGraphics { fun dispose() }

@Remote("java.awt.image.RenderedImage")
internal interface RemoteRenderedImage

@Remote("java.awt.image.BufferedImage")
internal interface RemoteCaptureImage : RemoteRenderedImage { fun createGraphics(): RemoteCaptureGraphics }

@Remote("java.io.File")
internal interface RemoteCaptureFile

@Remote("javax.imageio.ImageIO")
internal interface RemoteCaptureImageIO { fun write(image: RemoteRenderedImage, format: String, file: RemoteCaptureFile): Boolean }
