package com.reqws.goland.vcs

import com.intellij.openapi.vcs.ProjectLevelVcsManager
import com.intellij.openapi.vcs.VcsDirectoryMapping
import com.intellij.openapi.vcs.VcsRootSettings
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.reqws.goland.manifest.ManifestSnapshot
import com.reqws.goland.manifest.RepositoryAvailability
import com.reqws.goland.manifest.ResolvedRepository
import com.reqws.goland.manifest.WorkspaceManifest
import com.reqws.goland.manifest.WorkspaceRepository
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import org.jdom.Element

class ReqwsVcsDiagnosticsServiceTest : BasePlatformTestCase() {
  fun testConvertsReadFailuresToAStableInspectionDiagnostic() {
    val platform = object : VcsInspectionPlatform {
      override fun isGitAvailable(): Boolean = error("read failed")

      override fun getDirectoryMappings(): List<VcsDirectoryMapping> = error("unreachable")
    }

    val result = ReqwsVcsDiagnosticsService.inspectWithPlatform(
      snapshot(projectRoot(), emptyList()),
      platform,
    )

    assertEquals(emptyList<VcsRepositoryInspection>(), result.repositoryStatuses)
    assertEquals(
      listOf(VcsWorkspaceDiagnosticCode.INSPECTION_FAILED),
      result.workspaceDiagnostics,
    )
    assertEquals("VCS_DIAGNOSTIC_FAILED", result.stableErrorCode())
  }

  fun testDoesNotReadMappingsWhenGitIntegrationIsUnavailable() {
    var mappingReads = 0
    val platform = object : VcsInspectionPlatform {
      override fun isGitAvailable(): Boolean = false

      override fun getDirectoryMappings(): List<VcsDirectoryMapping> {
        mappingReads += 1
        return emptyList()
      }
    }

    val result = ReqwsVcsDiagnosticsService.inspectWithPlatform(
      snapshot(projectRoot(), emptyList()),
      platform,
    )

    assertEquals(0, mappingReads)
    assertEquals(
      listOf(VcsWorkspaceDiagnosticCode.GIT_PLUGIN_UNAVAILABLE),
      result.workspaceDiagnostics,
    )
  }

  fun testConvertsDirectoryMappingReadFailuresToAStableInspectionDiagnostic() {
    val platform = object : VcsInspectionPlatform {
      override fun isGitAvailable(): Boolean = true

      override fun getDirectoryMappings(): List<VcsDirectoryMapping> = error("mapping read failed")
    }

    val result = ReqwsVcsDiagnosticsService.inspectWithPlatform(
      snapshot(projectRoot(), emptyList()),
      platform,
    )

    assertEquals(
      listOf(VcsWorkspaceDiagnosticCode.INSPECTION_FAILED),
      result.workspaceDiagnostics,
    )
  }

  fun testCanonicalizesExactDirectoryDuplicatesUsingTheLastCompleteMapping() {
    val root = projectRoot()
    val repository = root.resolve("canonical-repository")
    Files.createDirectories(repository.resolve(".git"))
    val first = VcsDirectoryMapping(
      repository.toString(),
      GIT_VCS_NAME,
      DiagnosticsTestRootSettings("first"),
    )
    val winnerSettings = DiagnosticsTestRootSettings("winner")
    val winner = VcsDirectoryMapping(repository.toString(), "Mercurial", winnerSettings)
    val platform = object : VcsInspectionPlatform {
      override fun isGitAvailable(): Boolean = true

      override fun getDirectoryMappings(): List<VcsDirectoryMapping> = listOf(first, winner)
    }

    val result = ReqwsVcsDiagnosticsService.inspectWithPlatform(
      snapshot(root, listOf("canonical-repository")),
      platform,
    )

    assertEquals(VcsRepositoryStatus.WRONG_VCS, result.repositoryStatuses.single().status)
    assertEquals("VCS_CONFIGURATION_MISMATCH", result.stableErrorCode())
  }

  fun testPublicPlatformMappingsAndRootSettingsRemainByteForByteUnchanged() {
    val root = projectRoot()
    val repository = root.resolve("read-only-repository")
    Files.createDirectories(repository.resolve(".git"))
    val manager = ProjectLevelVcsManager.getInstance(project)
    val original = manager.getDirectoryMappings().toList()
    val settings = DiagnosticsTestRootSettings("user-owned")
    val userMapping = VcsDirectoryMapping(repository.toString(), GIT_VCS_NAME, settings)

    try {
      manager.setDirectoryMappings(listOf(userMapping))
      val before = manager.getDirectoryMappings().toList()

      ReqwsVcsDiagnosticsService(project).inspect(snapshot(root, listOf("read-only-repository")))

      val after = manager.getDirectoryMappings().toList()
      assertEquals(before, after)
      assertSame(before.single().rootSettings, after.single().rootSettings)
      assertSame(settings, after.single().rootSettings)
    } finally {
      manager.setDirectoryMappings(original)
    }
  }

  fun testInspectionPlatformExposesOnlyReadOperations() {
    assertEquals(
      setOf("isGitAvailable", "getDirectoryMappings"),
      VcsInspectionPlatform::class.java.declaredMethods.map { it.name }.toSet(),
    )
  }

  fun testLegacyOwnershipArtifactsDoNotChangeDiagnosticsAndRemainUnmodified() {
    val root = projectRoot()
    val idea = Files.createDirectories(root.resolve(".idea"))
    val stateFile = idea.resolve("reqws-vcs-ownership.json")
    val lockFile = idea.resolve(".reqws-vcs-ownership.json.lock")
    val stateBytes = "legacy ownership must remain inert".toByteArray()
    val lockBytes = "legacy lock must remain inert".toByteArray()
    val timestamp = FileTime.fromMillis(1_700_000_000_000L)
    Files.write(stateFile, stateBytes)
    Files.write(lockFile, lockBytes)
    Files.setLastModifiedTime(stateFile, timestamp)
    Files.setLastModifiedTime(lockFile, timestamp)

    val first = ReqwsVcsDiagnosticsService(project).inspect(snapshot(root, emptyList()))
    val secondStateBytes = "different untrusted legacy ownership content".toByteArray()
    Files.write(stateFile, secondStateBytes)
    Files.setLastModifiedTime(stateFile, timestamp)
    val second = ReqwsVcsDiagnosticsService(project).inspect(snapshot(root, emptyList()))

    assertEquals(first, second)
    assertTrue(secondStateBytes.contentEquals(Files.readAllBytes(stateFile)))
    assertTrue(lockBytes.contentEquals(Files.readAllBytes(lockFile)))
    assertEquals(timestamp, Files.getLastModifiedTime(stateFile))
    assertEquals(timestamp, Files.getLastModifiedTime(lockFile))
  }

  private fun projectRoot(): Path {
    val root = Path.of(requireNotNull(project.basePath))
    Files.createDirectories(root)
    return root.toRealPath()
  }

  private fun snapshot(root: Path, repositoryNames: List<String>): ManifestSnapshot {
    val repositories = repositoryNames.mapIndexed { index, name ->
      val repository = WorkspaceRepository(
        catalogRepositoryId = "repo_$index",
        name = name,
        url = "https://example.invalid/repository.git",
        defaultBranch = "main",
        relativePath = name,
      )
      val path = root.resolve(name)
      ResolvedRepository(
        repository = repository,
        path = path,
        canonicalPath = if (Files.exists(path)) path.toRealPath() else null,
        availability = if (Files.exists(path)) {
          RepositoryAvailability.PRESENT
        } else {
          RepositoryAvailability.MISSING
        },
      )
    }
    return ManifestSnapshot(
      manifest = WorkspaceManifest(
        schemaVersion = 1,
        id = "ws_test",
        name = "test",
        featureBranch = "feature/test",
        rootPath = root.toString(),
        workspaceFilePath = root.resolveSibling("test.code-workspace").toString(),
        repositories = repositories.map { it.repository },
        createdAt = "2026-08-14T00:00:00.000Z",
        updatedAt = "2026-08-14T00:00:00.000Z",
      ),
      manifestPath = root.resolve(".reqws/workspace.json"),
      canonicalProjectRoot = root,
      repositories = repositories,
      digestSha256 = "a".repeat(64),
      diagnostics = emptyList(),
    )
  }
}

private data class DiagnosticsTestRootSettings(private val id: String) : VcsRootSettings {
  override fun readExternal(element: Element) = Unit

  override fun writeExternal(element: Element) = Unit
}
