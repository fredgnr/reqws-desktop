package com.reqws.goland.vcs

import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import com.intellij.openapi.vcs.ProjectLevelVcsManager
import com.intellij.openapi.vcs.VcsDirectoryMapping
import com.reqws.goland.manifest.ManifestSnapshot

internal interface VcsInspectionPlatform {
  fun isGitAvailable(): Boolean

  fun getDirectoryMappings(): List<VcsDirectoryMapping>
}

private class IntellijVcsInspectionPlatform(project: Project) : VcsInspectionPlatform {
  private val vcsManager = ProjectLevelVcsManager.getInstance(project)

  override fun isGitAvailable(): Boolean = vcsManager.findVcsByName(GIT_VCS_NAME) != null

  override fun getDirectoryMappings(): List<VcsDirectoryMapping> =
    vcsManager.getDirectoryMappings().toList()
}

/** Reads GoLand's user-owned VCS configuration and reports manual follow-up; never mutates it. */
@Service(Service.Level.PROJECT)
internal class ReqwsVcsDiagnosticsService(project: Project) {
  private val platform: VcsInspectionPlatform = IntellijVcsInspectionPlatform(project)

  fun inspect(snapshot: ManifestSnapshot): VcsRootInspection = inspectWithPlatform(
    snapshot = snapshot,
    platform = platform,
  )

  companion object {
    internal fun inspectWithPlatform(
      snapshot: ManifestSnapshot,
      platform: VcsInspectionPlatform,
      inspector: VcsRootInspector = VcsRootInspector(),
    ): VcsRootInspection = try {
      val gitAvailable = platform.isGitAvailable()
      val mappings = if (gitAvailable) {
        val lastByDirectory = linkedMapOf<String, VcsDirectoryMapping>()
        platform.getDirectoryMappings().forEach { mapping ->
          lastByDirectory[mapping.directory] = mapping
        }
        lastByDirectory.values.sortedBy(VcsDirectoryMapping::getDirectory).map { mapping ->
          ObservedVcsMapping(
            directory = mapping.directory,
            vcs = mapping.vcs,
            hasRootSettings = mapping.rootSettings != null,
          )
        }
      } else {
        emptyList()
      }
      inspector.inspect(snapshot, gitAvailable, mappings)
    } catch (_: Exception) {
      VcsRootInspection.inspectionFailed()
    }
  }
}
