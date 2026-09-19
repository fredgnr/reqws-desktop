package com.reqws.goland.loading.shell

import com.intellij.ide.projectView.ProjectView
import com.intellij.ide.projectView.TreeStructureProvider
import com.intellij.ide.projectView.ViewSettings
import com.intellij.ide.util.treeView.AbstractTreeNode
import com.intellij.ide.trustedProjects.TrustedProjects
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.EDT
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.RootsChangeRescanningInfo
import com.intellij.openapi.roots.ex.ProjectRootManagerEx
import com.intellij.openapi.roots.impl.DirectoryIndexExcludePolicy
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.psi.PsiFileSystemItem
import com.reqws.goland.loading.contract.VerifiedBinding
import com.reqws.goland.projectmodel.ReqwsProjectModelMutationGuard
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicReference

internal data class ShellCapability(val bindingId: String, val directoryKey: String, val url: String)

@Service(Service.Level.PROJECT)
internal class ShellPresentationCache(private val project: Project) {
  private val cache = AtomicReference<ShellCapability?>()

  fun current(): ShellCapability? = if (project.isDisposed || !TrustedProjects.isProjectTrusted(project)) null else cache.get()

  /** One genuine policy transition, including empty/cold/revoked bindings; no entity churn. */
  suspend fun publish(binding: VerifiedBinding?, stillCurrent: () -> Boolean = { true }) {
    val next = binding?.let { ShellCapability(it.bindingId, it.directories.last().fileKey, VfsUtilCore.pathToUrl(it.shell.toString())) }
    if (next == null && cache.get() == null) return
    withContext(Dispatchers.EDT) {
      if (project.isDisposed || !stillCurrent()) return@withContext
      val accepted = if (TrustedProjects.isProjectTrusted(project)) next else null
      if (cache.get() == accepted) return@withContext
      ApplicationManager.getApplication().runWriteAction {
        if (!stillCurrent()) return@runWriteAction
        project.service<ReqwsProjectModelMutationGuard>().withMutation {
          ProjectRootManagerEx.getInstanceEx(project).makeRootsChange(
            { cache.set(accepted) }, RootsChangeRescanningInfo.TOTAL_RESCAN,
          )
        }
      }
      ProjectView.getInstance(project).refresh()
    }
  }
}

internal class ReqwsShellExcludePolicy(private val project: Project) : DirectoryIndexExcludePolicy {
  override fun getExcludeUrlsForProject(): Array<String> =
    project.service<ShellPresentationCache>().current()?.let { arrayOf(it.url) } ?: emptyArray()
}

internal class ReqwsShellTreeProvider(private val project: Project) : TreeStructureProvider {
  override fun modify(parent: AbstractTreeNode<*>, children: Collection<AbstractTreeNode<*>>, settings: ViewSettings): Collection<AbstractTreeNode<*>> {
    val shell = project.service<ShellPresentationCache>().current() ?: return children
    return children.filterNot { node ->
      val file = when (val value = node.value) {
        is PsiFileSystemItem -> value.virtualFile
        is VirtualFile -> value
        else -> null // Project/module/library containers must survive even with a shell descendant.
      }
      file != null && isShellNode(shell.url, file.url)
    }
  }
}

internal fun isShellNode(shellUrl: String, nodeUrl: String): Boolean =
  nodeUrl == shellUrl || nodeUrl.startsWith(shellUrl.trimEnd('/') + "/")
