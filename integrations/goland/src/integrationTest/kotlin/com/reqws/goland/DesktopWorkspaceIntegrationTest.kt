package com.reqws.goland

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import com.intellij.driver.client.Driver
import com.intellij.driver.client.service
import com.intellij.driver.client.utility
import com.intellij.driver.sdk.invokeAction
import com.intellij.driver.sdk.isPluginLoaded
import com.intellij.driver.sdk.singleProject
import com.intellij.driver.sdk.ui.components.elements.checkBox
import com.intellij.driver.sdk.ui.ui
import com.intellij.driver.sdk.waitFor
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.nio.file.Files
import java.nio.file.LinkOption
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DesktopWorkspaceIntegrationTest {
  private val host = IdeScenarioHost()
  private lateinit var desktop: DesktopLink

  @BeforeAll
  fun prepareEnvironment() {
    host.prepare()
    desktop = DesktopLink(host.root)
  }

  @Test
  fun desktopSelectionAndColdProcesses() {
    val fixture = desktop.create("selection")
    val context = host.context("desktop-selection", fixture.shell)
    host.withIde(context) {
      host.assertProjection(this, fixture)
      for (selection in listOf(setOf("repo-a"), emptySet(), setOf("repo-a", "repo-b"), emptySet())) {
        desktop.select(fixture, selection)
        host.assertProjection(this, fixture)
      }
    }
    // Complete process shutdown is checked by the host before either new process starts.
    host.withIde(context) {
      host.assertProjection(this, fixture)
      desktop.select(fixture, setOf("repo-a", "repo-b"))
      host.assertProjection(this, fixture)
    }
    host.withIde(context) { host.assertProjection(this, fixture) }
    fixture.assertDiskPreserved()
  }

  @Test
  fun desktopTrustTransitionUsesRealUi() {
    // Safe mode may intentionally ignore pre-existing JPS metadata. This fresh project has
    // no seeded user module; preservation of user roots is asserted in the other scenarios.
    val fixture = desktop.create("trust", userModel = false)
    val bindingBytes = desktop.readBytes(fixture.shell.resolve("reqws-project.json"))
    val manifestBytes = desktop.readBytes(fixture.root.resolve(".reqws/workspace.json"))
    val context = host.context("desktop-trust", fixture.shell, preTrustProject = false)
    host.withIde(context, beforeProjectOpen = {
      val preview = ui.x { byVisibleText("Preview in Safe Mode") }
      waitFor("real untrusted-project preview dialog", 2.minutes) {
        desktop.checkAbort()
        preview.present()
      }
      uncheckTrustParent()
      preview.click()
    }) {
      assertFalse(isPluginLoaded("com.ypwang.plugin.go-linter"), "Only the trust scenario isolates the unrelated bundled linter")
      val trust = utility<RemoteTrustedProjects>()
      val reqws = service<ReqwsRemoteService>(singleProject())
      assertFalse(trust.isProjectTrusted(singleProject()), "The trust scenario was bypassed")
      waitFor("ReqWS blocked by the actual IDE trust state", 2.minutes) {
        desktop.checkAbort()
        reqws.getState().getLifecycle().name() == "SAFE_MODE_BLOCKED"
      }
      val blocked = reqws.getState()
      assertNull(blocked.getValidatedProjectionDigest())
      assertNull(blocked.getLastAppliedDigest())
      val before = host.modelEvidence(this, fixture)
      assertTrue(before.modules.keys.none { it.startsWith("ReqWS-", ignoreCase = true) })
      for (name in fixture.repositories.keys) {
        assertFalse(fixture.root.resolve(name).toString() in before.roots)
        assertFalse(before.pfi.getValue(fixture.root.resolve(name).toString()).getValue("inContent"))
      }
      assertFalse(Files.exists(fixture.shell.resolve(".idea/reqws-loaded-roots.json"), LinkOption.NOFOLLOW_LINKS))
      assertFalse(Files.exists(fixture.shell.resolve(".idea/reqws"), LinkOption.NOFOLLOW_LINKS))
      fixture.verifyInputs()
      fixture.assertDiskPreserved()
      host.recordProof(this, fixture, "safe-mode-blocked", before)

      // Open the IDE's normal user-facing Trust Project action; only its actual button
      // changes trust. No remote setter or plugin refresh/sync API is exposed here.
      invokeAction("ShowTrustProjectDialog", false)
      val trustButton = ui.x { byVisibleText("Trust Project") }
      waitFor("real Trust Project confirmation", 30.seconds) { trustButton.present() }
      uncheckTrustParent()
      trustButton.click()
      waitFor("IDE trust changes after confirmation", 30.seconds) { trust.isProjectTrusted(singleProject()) }
      host.assertProjection(this, fixture)
      assertTrue(bindingBytes.contentEquals(desktop.readBytes(fixture.shell.resolve("reqws-project.json"))))
      assertTrue(manifestBytes.contentEquals(desktop.readBytes(fixture.root.resolve(".reqws/workspace.json"))))
    }
  }

  @Test
  fun desktopInvalidInputsPreserveUserModel() {
    for (name in listOf("invalid-binding", "invalid-manifest")) {
      val fixture = desktop.create(name)
      host.withIde(host.context("desktop-$name", fixture.shell)) {
        host.assertProjection(this, fixture)
        val path = if (name == "invalid-binding") fixture.shell.resolve("reqws-project.json")
          else fixture.root.resolve(".reqws/workspace.json")
        val original = desktop.readBytes(path)
        val mismatched = (ObjectMapper().readTree(original) as ObjectNode).apply {
          put(if (name == "invalid-binding") "workspaceId" else "id", "foreign-${fixture.workspaceId}")
        }
        val faults = listOf(
          Triple("malformed", "{broken".toByteArray(), if (name == "invalid-binding") "BINDING_ERROR" else "MANIFEST_INVALID_JSON"),
          Triple("mismatched", ObjectMapper().writeValueAsBytes(mismatched), "BINDING_ERROR"),
        )
        for ((phase, bytes, errorCode) in faults) {
          val before = host.modelEvidence(this, fixture)
          desktop.faultWrite(path, bytes)
          try {
            val service = service<ReqwsRemoteService>(singleProject())
            waitFor("real watcher rejects $name/$phase", 2.minutes) {
              desktop.checkAbort()
              val state = service.getState()
              state.getLifecycle().name() == "ERROR" && state.getLastError()?.getCode() == errorCode
            }
            val after = host.modelEvidence(this, fixture)
            assertEquals(before.roots, after.roots, "Invalid input changed content roots")
            assertEquals(before.modules, after.modules, "Invalid input changed protected modules")
            // Invalid bindings revoke the transient shell hiding capability. The
            // native shell root remains, while repository/user PFI must not change.
            val shellPath = fixture.shell.toString()
            assertEquals(before.pfi.filterKeys { it != shellPath }, after.pfi.filterKeys { it != shellPath },
              "Invalid input changed repository/user ProjectFileIndex boundaries")
            assertEquals(mapOf("inContent" to true, "excluded" to false), after.pfi.getValue(shellPath))
            fixture.assertDiskPreserved()
            val tree = host.projectTree(this, fixture, phase)
            assertTrue(tree.any { path -> path.any { it.substringBefore(" [").substringBefore(" /") == "user-content" } && path.last() == "keep.txt" })
            assertTrue(tree.any { path -> path.any { it.substringBefore(" [").substringBefore(" /") == "goland" } },
              "The invalid binding must not keep hiding the native shell")
            host.recordProof(this, fixture, phase, after, tree)
          } finally {
            // Restore only the original bytes of this private fixture input, even on failure.
            desktop.faultWrite(path, original, restoring = true)
          }
          host.assertProjection(this, fixture)
        }
      }
      fixture.assertDiskPreserved()
    }
  }

  private fun Driver.uncheckTrustParent() {
    val checkbox = ui.checkBox { contains(byText("Trust all projects")) }
    if (checkbox.present()) {
      checkbox.uncheck()
      assertFalse(checkbox.isSelected())
    }
  }
}
