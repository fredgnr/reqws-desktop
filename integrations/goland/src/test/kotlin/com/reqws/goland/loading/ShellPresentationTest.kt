package com.reqws.goland.loading

import com.intellij.ide.projectView.PresentationData
import com.intellij.ide.projectView.ViewSettings
import com.intellij.ide.util.treeView.AbstractTreeNode
import com.intellij.openapi.components.service
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.platform.backend.workspace.WorkspaceModel
import com.intellij.platform.workspace.jps.entities.ContentRootEntity
import com.intellij.platform.workspace.jps.entities.ModuleId
import com.intellij.platform.workspace.jps.entities.modifyModuleEntity
import com.intellij.testFramework.HeavyPlatformTestCase
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.util.concurrency.AppExecutorUtil
import com.reqws.goland.loading.contract.VerifiedBinding
import com.reqws.goland.loading.contract.boundDirectory
import com.reqws.goland.loading.shell.ShellPresentationCache
import com.reqws.goland.loading.shell.ReqwsShellTreeProvider
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.Callable

class ShellPresentationTest : HeavyPlatformTestCase() {
  fun testRegisteredExclusionAndTreeFilterRevokeWithoutModelChanges() {
    val root = Files.createTempDirectory("reqws-shell-policy-").toRealPath()
    val shell = root.resolve(".reqws/ide/goland")
    Files.createDirectories(shell)
    Files.writeString(shell.resolve("probe.txt"), "shell")
    Files.createDirectories(root.resolve("elsewhere/goland"))
    try {
      val model = WorkspaceModel.getInstance(project)
      awaitUpdate { model.update("Fixture native shell root") { builder ->
        val entity = builder.resolve(ModuleId(module.name))!!
        builder.modifyModuleEntity(entity) { contentRoots += ContentRootEntity(model.getVirtualFileUrlManager().fromPath(root.toString()), emptyList(), entity.entitySource) }
      } }
      val fs = LocalFileSystem.getInstance()
      val shellFile = fs.refreshAndFindFileByNioFile(shell)!!
      val probe = fs.refreshAndFindFileByNioFile(shell.resolve("probe.txt"))!!
      val userFile = fs.refreshAndFindFileByNioFile(root.resolve("elsewhere/goland"))!!
      val binding = VerifiedBinding("ws_1", "binding", root, shell, listOf(root, shell.parent.parent, shell.parent, shell).map(::boundDirectory))
      val cache = project.service<ShellPresentationCache>()
      val index = ProjectFileIndex.getInstance(project)
      assertTrue(index.isInContent(probe))
      val before = model.currentSnapshot
      awaitUpdate { cache.publish(binding) }
      assertSame(before, model.currentSnapshot)
      assertTrue(index.isExcluded(probe))
      assertFalse(index.isInContent(probe))
      val shellNode = node(shellFile)
      val probeNode = node(probe)
      val userNode = node(userFile)
      val container = node(module)
      val parent = node(project)
      val children = listOf(shellNode, probeNode, userNode, container)
      val provider = ReqwsShellTreeProvider(project)
      assertEquals(listOf(userNode, container), provider.modify(parent, children, ViewSettings.DEFAULT))
      // Filtering is independent of view options and never infers identity from a displayed name.
      assertEquals(listOf(userNode, container), provider.modify(parent, children, object : ViewSettings {}))
      awaitUpdate { cache.publish(null) }
      assertSame(before, model.currentSnapshot)
      assertTrue(index.isInContent(probe))
      assertEquals(children, provider.modify(parent, children, ViewSettings.DEFAULT))
    } finally { root.toFile().deleteRecursively() }
  }

  private fun node(value: Any) = object : AbstractTreeNode<Any>(project, value) {
    override fun getChildren(): Collection<AbstractTreeNode<*>> = emptyList()
    override fun update(presentation: PresentationData) {}
  }
  private fun <T> awaitUpdate(block: suspend () -> T): T = PlatformTestUtil.waitForFuture(AppExecutorUtil.getAppExecutorService().submit(Callable { runBlocking { block() } }))
}
