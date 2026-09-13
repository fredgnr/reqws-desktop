package com.reqws.goland.manifest

import java.nio.file.Path

data class WorkspaceRepository(
  val catalogRepositoryId: String,
  val name: String,
  val url: String,
  val defaultBranch: String,
  val relativePath: String,
) {
  override fun toString(): String = "WorkspaceRepository(<redacted>)"
}

data class WorkspaceManifest(
  val schemaVersion: Int,
  val id: String,
  val name: String,
  val featureBranch: String,
  val rootPath: String,
  val workspaceFilePath: String,
  val repositories: List<WorkspaceRepository>,
  val createdAt: String,
  val updatedAt: String,
) {
  override fun toString(): String =
    "WorkspaceManifest(schemaVersion=$schemaVersion, repositoryCount=${repositories.size})"
}

enum class RepositoryAvailability {
  PRESENT,
  MISSING,
}

data class ResolvedRepository(
  val repository: WorkspaceRepository,
  val path: Path,
  val canonicalPath: Path?,
  val availability: RepositoryAvailability,
) {
  override fun toString(): String = "ResolvedRepository(availability=$availability)"
}

data class ManifestSnapshot(
  val manifest: WorkspaceManifest,
  val manifestPath: Path,
  val canonicalProjectRoot: Path,
  val repositories: List<ResolvedRepository>,
  val digestSha256: String,
  val diagnostics: List<ManifestDiagnostic>,
) {
  val missingRepositoryCount: Int
    get() = repositories.count { it.availability == RepositoryAvailability.MISSING }

  override fun toString(): String =
    "ManifestSnapshot(digestSha256=$digestSha256, " +
      "repositoryCount=${repositories.size}, missingRepositoryCount=$missingRepositoryCount)"
}
