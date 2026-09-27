package com.reqws.goland

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText

internal class WorkspaceFixture(parent: Path) : ProjectionFixture {
  override val root: Path = Files.createTempDirectory(parent, "workspace-").toRealPath()
  override val shell: Path = root.resolve(".reqws/ide/goland").createDirectories()
  override val bindingId = UUID.randomUUID().toString()
  override val workspaceId = "ws_fixture"
  override val repositories = mapOf("repo-a" to "repo-a", "repo-b" to "repo-b")
  override var selected: Set<String> = emptySet()
    private set
  override var revision = 0L
    private set

  init {
    for (name in listOf("repo-a", "repo-b")) {
      val repo = root.resolve(name).createDirectories()
      repo.resolve("docs").createDirectories().resolve("probe.txt").writeText("ordinary text fixture\n")
      val process = ProcessBuilder("git", "init", "--quiet", repo.toString()).redirectErrorStream(true).start()
      check(process.waitFor(20, TimeUnit.SECONDS)) { "Fixture Git initialization timed out" }
      check(process.exitValue() == 0) { "Fixture Git initialization failed" }
    }
    root.resolve("user-content").createDirectories().resolve("keep.txt").writeText("user owned\n")
    val repositories = listOf("repo-a", "repo-b").joinToString(",") { name ->
      """{"catalogRepositoryId":"$name","name":"$name","url":"https://example.test/$name.git","defaultBranch":"main","relativePath":"$name"}"""
    }
    root.resolve(".reqws/workspace.json").writeText("""
      {"schemaVersion":1,"id":"ws_fixture","name":"ReqWS automation","featureBranch":"fixture",
       "rootPath":${json(root.toString())},"workspaceFilePath":${json(root.resolve("fixture.code-workspace").toString())},
       "repositories":[$repositories],"createdAt":"2026-09-21T00:00:00.000Z","updatedAt":"2026-09-21T00:00:00.000Z"}
    """.trimIndent())
    seedUserModel(this)
    select(listOf("repo-a", "repo-b"))
  }

  fun select(ids: List<String>) {
    selected = ids.toSet()
    revision += 1
    val temporary = shell.resolve("reqws-project-${UUID.randomUUID()}.tmp")
    temporary.writeText("""
      {"schemaVersion":1,"adapterProtocol":1,"workspaceId":"ws_fixture","bindingId":"$bindingId",
       "revision":$revision,"selection":{"mode":"selected","repositoryIds":[${ids.joinToString(",", transform = ::json)}]},
       "updatedAt":"2026-09-21T00:00:00.000Z"}
    """.trimIndent())
    Files.move(temporary, shell.resolve("reqws-project.json"), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
  }

  override fun assertDiskPreserved() {
    for (name in listOf("repo-a", "repo-b")) {
      check(Files.isDirectory(root.resolve("$name/.git")))
      check(Files.readString(root.resolve("$name/docs/probe.txt")) == "ordinary text fixture\n")
    }
    check(Files.readString(root.resolve("user-content/keep.txt")) == "user owned\n")
  }

  override fun verifyInputs() = Unit

  private fun json(value: String) = "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
}
