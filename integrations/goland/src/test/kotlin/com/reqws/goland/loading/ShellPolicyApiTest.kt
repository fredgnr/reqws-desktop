package com.reqws.goland.loading

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.RootsChangeRescanningInfo
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.roots.ex.ProjectRootManagerEx
import com.intellij.openapi.roots.impl.DirectoryIndexExcludePolicy
import com.intellij.platform.backend.workspace.WorkspaceModel
import com.intellij.testFramework.fixtures.BasePlatformTestCase

/** S0: exercise the exact target SDK without Experimental notification APIs. */
class ShellPolicyApiTest : BasePlatformTestCase() {
  override fun isWriteActionRequired(): Boolean = false

  fun testPolicyTransitionsInvalidatePfiWithoutChangingWorkspaceEntities() {
    val shell = myFixture.tempDirFixture.findOrCreateDir(".reqws/ide/goland")
    val probe = myFixture.tempDirFixture.createFile(".reqws/ide/goland/probe.txt", "shell")
    val repo = myFixture.tempDirFixture.createFile("repo/probe.txt", "repository")
    var shellUrl: String? = null
    val policy = object : DirectoryIndexExcludePolicy {
      override fun getExcludeUrlsForProject(): Array<String> = listOfNotNull(shellUrl).toTypedArray()
    }
    project.extensionArea.getExtensionPoint<DirectoryIndexExcludePolicy>(DirectoryIndexExcludePolicy.EP_NAME.name)
      .registerExtension(policy, testRootDisposable)
    val index = ProjectFileIndex.getInstance(project)
    val snapshot = WorkspaceModel.getInstance(project).currentSnapshot
    assertTrue(index.isInContent(probe))
    fun publish(url: String?) {
      ApplicationManager.getApplication().runWriteAction {
        ProjectRootManagerEx.getInstanceEx(project).makeRootsChange(
          { shellUrl = url },
          RootsChangeRescanningInfo.TOTAL_RESCAN,
        )
      }
    }
    // Initial trust, revoked binding, restored binding, Safe Mode, then trusted empty selection.
    for (url in listOf(shell.url, null, shell.url, null, shell.url)) {
      publish(url)
      assertEquals(url != null, index.isExcluded(probe))
      assertEquals(url == null, index.isInContent(probe))
      assertTrue(index.isInContent(repo))
      assertSame(snapshot, WorkspaceModel.getInstance(project).currentSnapshot)
    }
    val late = myFixture.tempDirFixture.createFile(".reqws/ide/goland/late.txt", "late shell")
    assertTrue(index.isExcluded(late))
    assertFalse(index.isInContent(late))
    publish(null)
    assertTrue(index.isInContent(late))
  }
}
