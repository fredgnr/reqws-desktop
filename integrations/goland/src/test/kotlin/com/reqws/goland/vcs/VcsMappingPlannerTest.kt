package com.reqws.goland.vcs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Files
import java.nio.file.Path

class VcsMappingPlannerTest {
  @Rule
  @JvmField
  val temporaryFolder = TemporaryFolder()

  private val planner = VcsMappingPlanner()

  @Test
  fun `adds missing Git roots and preserves every user mapping`() {
    val root = workspaceRoot()
    val user = root.resolve("user-owned")
    val current = listOf(mapping(0, user, "Mercurial"))

    val plan = planner.plan(
      root,
      current,
      emptyList(),
      listOf(desired(root, "repo-a", 0), desired(root, "repo-b", 1)),
    )

    assertEquals(listOf(root.resolve("repo-a").toString(), root.resolve("repo-b").toString()), plan.additions.map { it.directory })
    assertTrue(plan.removalIndices.isEmpty())
    assertEquals(
      listOf("repo-a", "repo-b"),
      plan.nextOwnership.map { it.relativeDirectory },
    )
    assertTrue(plan.nextOwnership.all { it.kind == VcsMappingOwnershipKind.CREATED })
  }

  @Test
  fun `borrows an equivalent user Git mapping and never removes it later`() {
    val root = workspaceRoot()
    val repo = root.resolve("repo-a")
    val current = listOf(mapping(0, repo, GIT_VCS_NAME))

    val adopted = planner.plan(root, current, emptyList(), listOf(desired(root, "repo-a", 0)))

    assertTrue(adopted.additions.isEmpty())
    assertTrue(adopted.removalIndices.isEmpty())
    assertEquals(VcsMappingOwnershipKind.BORROWED, adopted.nextOwnership.single().kind)

    val removedFromManifest = planner.plan(root, current, adopted.nextOwnership, emptyList())

    assertTrue(removedFromManifest.removalIndices.isEmpty())
    assertTrue(removedFromManifest.nextOwnership.isEmpty())
  }

  @Test
  fun `removes only a unique exact Git mapping with created ownership`() {
    val root = workspaceRoot()
    val repo = root.resolve("repo-a")
    val user = root.resolve("user-owned")
    val current = listOf(
      mapping(0, repo, GIT_VCS_NAME),
      mapping(1, user, "Mercurial"),
    )

    val plan = planner.plan(
      root,
      current,
      listOf(VcsMappingOwnership("repo-a", VcsMappingOwnershipKind.CREATED)),
      emptyList(),
    )

    assertEquals(setOf(0), plan.removalIndices)
    assertFalse(1 in plan.removalIndices)
    assertTrue(plan.nextOwnership.isEmpty())
  }

  @Test
  fun `preserves a manually changed or duplicated owned mapping and reports conflict`() {
    val root = workspaceRoot()
    val repo = root.resolve("repo-a")
    val ownership = listOf(VcsMappingOwnership("repo-a", VcsMappingOwnershipKind.CREATED))

    val changed = planner.plan(
      root,
      listOf(mapping(0, repo, "Mercurial")),
      ownership,
      emptyList(),
    )
    val duplicated = planner.plan(
      root,
      listOf(mapping(0, repo, GIT_VCS_NAME), mapping(1, repo, GIT_VCS_NAME)),
      ownership,
      emptyList(),
    )

    assertTrue(changed.removalIndices.isEmpty())
    assertTrue(duplicated.removalIndices.isEmpty())
    assertTrue(changed.diagnostics.any { it.code == VcsMappingDiagnosticCode.OWNERSHIP_CONFLICT })
    assertTrue(duplicated.diagnostics.any { it.code == VcsMappingDiagnosticCode.OWNERSHIP_CONFLICT })
  }

  @Test
  fun `never keeps created deletion authority after user root settings are added`() {
    val root = workspaceRoot()
    val repository = root.resolve("repo-a")
    val customized = mapping(0, repository, GIT_VCS_NAME).copy(hasRootSettings = true)
    val ownership = listOf(VcsMappingOwnership("repo-a", VcsMappingOwnershipKind.CREATED))

    val active = planner.plan(
      root,
      listOf(customized),
      ownership,
      listOf(desired(root, "repo-a", 0)),
    )
    val missing = planner.plan(
      root,
      listOf(customized),
      ownership,
      listOf(
        DesiredVcsRoot(
          repositoryIndex = 0,
          relativeDirectory = "repo-a",
          directory = null,
          availability = DesiredVcsRootAvailability.MISSING,
        ),
      ),
    )

    assertTrue(active.removalIndices.isEmpty())
    assertEquals(VcsMappingOwnershipKind.BORROWED, active.nextOwnership.single().kind)
    assertTrue(active.diagnostics.any { it.code == VcsMappingDiagnosticCode.OWNERSHIP_CONFLICT })
    assertTrue(missing.removalIndices.isEmpty())
    assertTrue(missing.nextOwnership.isEmpty())
    assertTrue(missing.diagnostics.any { it.code == VcsMappingDiagnosticCode.OWNERSHIP_CONFLICT })
  }

  @Test
  fun `a non-canonical textual mapping is not an exact owned mapping`() {
    val root = workspaceRoot()
    val repo = Files.createDirectory(root.resolve("repo-a"))
    val dottedDirectory = repo.resolve(".").toString() + "/."
    val current = CurrentVcsMapping(
      index = 0,
      directory = dottedDirectory,
      vcs = GIT_VCS_NAME,
      hasRootSettings = false,
      lexicalIdentity = VcsPathIdentity.mappingLexical(root, dottedDirectory),
      canonicalIdentity = VcsPathIdentity.mappingCanonical(root, dottedDirectory),
    )

    val plan = planner.plan(
      root,
      listOf(current),
      listOf(VcsMappingOwnership("repo-a", VcsMappingOwnershipKind.CREATED)),
      emptyList(),
    )

    assertTrue(plan.removalIndices.isEmpty())
    assertTrue(plan.diagnostics.any { it.code == VcsMappingDiagnosticCode.OWNERSHIP_CONFLICT })
  }

  @Test
  fun `does not add Git beside an equivalent non-Git user mapping`() {
    val root = workspaceRoot()
    val repo = root.resolve("repo-a")

    val plan = planner.plan(
      root,
      listOf(mapping(0, repo, "Mercurial")),
      emptyList(),
      listOf(desired(root, "repo-a", 0)),
    )

    assertTrue(plan.additions.isEmpty())
    assertTrue(plan.removalIndices.isEmpty())
    assertTrue(plan.diagnostics.any { it.code == VcsMappingDiagnosticCode.OWNERSHIP_CONFLICT })
  }

  @Test
  fun `preserves but reports an extra user Git mapping inside the workspace`() {
    val root = workspaceRoot()
    val retained = root.resolve("retained")

    val plan = planner.plan(
      root,
      listOf(mapping(0, retained, GIT_VCS_NAME)),
      emptyList(),
      listOf(desired(root, "repo-a", 0)),
    )

    assertTrue(plan.removalIndices.isEmpty())
    assertTrue(plan.diagnostics.any { it.code == VcsMappingDiagnosticCode.OWNERSHIP_CONFLICT })
  }

  @Test
  fun `preserves but reports a project root Git mapping`() {
    val root = workspaceRoot()
    val projectMapping = CurrentVcsMapping(
      index = 0,
      directory = "",
      vcs = GIT_VCS_NAME,
      hasRootSettings = false,
      lexicalIdentity = null,
      canonicalIdentity = null,
    )

    val plan = planner.plan(
      root,
      listOf(projectMapping),
      emptyList(),
      listOf(desired(root, "repo-a", 0)),
    )

    assertTrue(plan.removalIndices.isEmpty())
    assertTrue(plan.diagnostics.any { it.code == VcsMappingDiagnosticCode.OWNERSHIP_CONFLICT })
  }

  @Test
  fun `reports extra and project root mappings under an NFD workspace root`() {
    val root = workspaceRoot().resolve("Cafe\u0301")
    val retained = root.resolve("retained")
    val projectMapping = CurrentVcsMapping(
      index = 0,
      directory = "",
      vcs = GIT_VCS_NAME,
      hasRootSettings = false,
      lexicalIdentity = null,
      canonicalIdentity = null,
    )

    val extraPlan = planner.plan(
      root,
      listOf(mapping(0, retained, GIT_VCS_NAME)),
      emptyList(),
      listOf(desired(root, "repo-a", 0)),
    )
    val rootPlan = planner.plan(
      root,
      listOf(projectMapping),
      emptyList(),
      listOf(desired(root, "repo-a", 0)),
    )

    assertTrue(extraPlan.degraded)
    assertTrue(rootPlan.degraded)
    assertTrue(extraPlan.diagnostics.any { it.code == VcsMappingDiagnosticCode.OWNERSHIP_CONFLICT })
    assertTrue(rootPlan.diagnostics.any { it.code == VcsMappingDiagnosticCode.OWNERSHIP_CONFLICT })
  }

  @Test
  fun `skips missing repository while synchronizing other valid roots`() {
    val root = workspaceRoot()
    val missing = DesiredVcsRoot(
      repositoryIndex = 0,
      relativeDirectory = "repo-missing",
      directory = null,
      availability = DesiredVcsRootAvailability.MISSING,
    )

    val plan = planner.plan(root, emptyList(), emptyList(), listOf(missing, desired(root, "repo-ok", 1)))

    assertEquals(listOf(root.resolve("repo-ok").toString()), plan.additions.map { it.directory })
    assertEquals(
      listOf(VcsMappingDiagnosticCode.REPOSITORY_MISSING),
      plan.diagnostics.map { it.code },
    )
    assertTrue(plan.degraded)
  }

  @Test
  fun `preserves a created mapping while its active repository is temporarily missing`() {
    val root = workspaceRoot()
    val repository = root.resolve("repo-a")
    val ownership = VcsMappingOwnership("repo-a", VcsMappingOwnershipKind.CREATED)
    val missing = DesiredVcsRoot(
      repositoryIndex = 0,
      relativeDirectory = "repo-a",
      directory = null,
      availability = DesiredVcsRootAvailability.MISSING,
    )

    val plan = planner.plan(
      root,
      listOf(mapping(0, repository, GIT_VCS_NAME)),
      listOf(ownership),
      listOf(missing),
    )

    assertTrue(plan.removalIndices.isEmpty())
    assertEquals(listOf(ownership), plan.nextOwnership)
    assertTrue(plan.diagnostics.any { it.code == VcsMappingDiagnosticCode.REPOSITORY_MISSING })
  }

  @Test
  fun `preserves a created mapping while its active repository is temporarily non Git`() {
    val root = workspaceRoot()
    val repository = root.resolve("repo-a")
    val ownership = VcsMappingOwnership("repo-a", VcsMappingOwnershipKind.CREATED)
    val nonGit = DesiredVcsRoot(
      repositoryIndex = 0,
      relativeDirectory = "repo-a",
      directory = repository.toString(),
      availability = DesiredVcsRootAvailability.NOT_GIT_REPOSITORY,
    )

    val plan = planner.plan(
      root,
      listOf(mapping(0, repository, GIT_VCS_NAME)),
      listOf(ownership),
      listOf(nonGit),
    )

    assertTrue(plan.removalIndices.isEmpty())
    assertEquals(listOf(ownership), plan.nextOwnership)
    assertTrue(plan.diagnostics.any { it.code == VcsMappingDiagnosticCode.REPOSITORY_NOT_GIT })
  }

  @Test
  fun `drops stale created ownership when an unavailable repository mapping disappeared`() {
    val root = workspaceRoot()
    val ownership = VcsMappingOwnership("repo-a", VcsMappingOwnershipKind.CREATED)
    val missing = DesiredVcsRoot(
      repositoryIndex = 0,
      relativeDirectory = "repo-a",
      directory = null,
      availability = DesiredVcsRootAvailability.MISSING,
    )

    val unavailable = planner.plan(root, emptyList(), listOf(ownership), listOf(missing))
    assertTrue(unavailable.nextOwnership.isEmpty())

    val replacement = root.resolve("repo-a")
    val laterInactive = planner.plan(
      root,
      listOf(mapping(0, replacement, GIT_VCS_NAME)),
      unavailable.nextOwnership,
      emptyList(),
    )
    assertTrue(laterInactive.removalIndices.isEmpty())
  }

  @Test
  fun `rejects escaped ownership without deleting any mapping`() {
    val root = workspaceRoot()
    val outside = root.resolveSibling("outside")

    val plan = planner.plan(
      root,
      listOf(mapping(0, outside, GIT_VCS_NAME)),
      listOf(VcsMappingOwnership("../outside", VcsMappingOwnershipKind.CREATED)),
      emptyList(),
    )

    assertTrue(plan.removalIndices.isEmpty())
    assertTrue(plan.diagnostics.any { it.code == VcsMappingDiagnosticCode.OWNERSHIP_CONFLICT })
  }

  @Test
  fun `rejects non-canonical and symlinked ownership paths`() {
    val root = workspaceRoot()
    val repository = Files.createDirectory(root.resolve("repository"))
    val alias = root.resolve("alias")
    Files.createSymbolicLink(alias, repository)
    val current = listOf(mapping(0, repository, GIT_VCS_NAME))

    val dotted = planner.plan(
      root,
      current,
      listOf(VcsMappingOwnership("repository/.", VcsMappingOwnershipKind.CREATED)),
      emptyList(),
    )
    val symlinked = planner.plan(
      root,
      current,
      listOf(VcsMappingOwnership("alias", VcsMappingOwnershipKind.CREATED)),
      emptyList(),
    )

    assertTrue(dotted.removalIndices.isEmpty())
    assertTrue(symlinked.removalIndices.isEmpty())
    assertTrue(dotted.diagnostics.any { it.code == VcsMappingDiagnosticCode.OWNERSHIP_CONFLICT })
    assertTrue(symlinked.diagnostics.any { it.code == VcsMappingDiagnosticCode.OWNERSHIP_CONFLICT })
  }

  @Test
  fun `normalizes a current symlink alias only for borrowing`() {
    val root = workspaceRoot()
    val repository = Files.createDirectory(root.resolve("repository"))
    val alias = root.resolve("alias")
    Files.createSymbolicLink(alias, repository)
    val current = listOf(
      CurrentVcsMapping(
        index = 0,
        directory = alias.toString(),
        vcs = GIT_VCS_NAME,
        hasRootSettings = false,
        lexicalIdentity = VcsPathIdentity.lexical(alias),
        canonicalIdentity = VcsPathIdentity.canonical(alias),
      ),
    )

    val plan = planner.plan(
      root,
      current,
      emptyList(),
      listOf(desired(root, "repository", 0)),
    )

    assertTrue(plan.additions.isEmpty())
    assertEquals(VcsMappingOwnershipKind.BORROWED, plan.nextOwnership.single().kind)
  }

  @Test
  fun `any number of duplicate corrupted ownership records never authorizes a removal`() {
    val root = workspaceRoot()
    val repo = root.resolve("repo-a")
    val ownership = listOf(
      VcsMappingOwnership("repo-a", VcsMappingOwnershipKind.CREATED),
      VcsMappingOwnership("repo-a", VcsMappingOwnershipKind.CREATED),
      VcsMappingOwnership("repo-a", VcsMappingOwnershipKind.CREATED),
    )

    val plan = planner.plan(
      root,
      listOf(mapping(0, repo, GIT_VCS_NAME)),
      ownership,
      emptyList(),
    )

    assertTrue(plan.removalIndices.isEmpty())
    assertTrue(plan.diagnostics.any { it.code == VcsMappingDiagnosticCode.OWNERSHIP_CONFLICT })
  }

  private fun workspaceRoot(): Path = temporaryFolder.newFolder().toPath().toRealPath()

  private fun desired(root: Path, relative: String, index: Int): DesiredVcsRoot =
    DesiredVcsRoot(
      repositoryIndex = index,
      relativeDirectory = relative,
      directory = root.resolve(relative).toString(),
      availability = DesiredVcsRootAvailability.PRESENT_GIT,
    )

  private fun mapping(index: Int, path: Path, vcs: String): CurrentVcsMapping =
    CurrentVcsMapping(
      index = index,
      directory = path.toString(),
      vcs = vcs,
      hasRootSettings = false,
      lexicalIdentity = VcsPathIdentity.lexical(path),
      canonicalIdentity = VcsPathIdentity.canonical(path),
    )
}
