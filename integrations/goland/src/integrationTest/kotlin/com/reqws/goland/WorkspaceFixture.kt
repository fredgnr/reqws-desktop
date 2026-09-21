package com.reqws.goland

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText

internal class WorkspaceFixture(parent: Path) {
  val root: Path = Files.createTempDirectory(parent, "workspace-").toRealPath()
  val shell: Path = root.resolve(".reqws/ide/goland").createDirectories()
  private val binding = UUID.randomUUID().toString()
  var revision = 0L
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
    val idea = shell.resolve(".idea").createDirectories()
    idea.resolve("modules.xml").writeText("""
      <project version="4"><component name="ProjectModuleManager"><modules>
      <module fileurl="file://${'$'}PROJECT_DIR${'$'}/.idea/shell.iml" filepath="${'$'}PROJECT_DIR${'$'}/.idea/shell.iml"/>
      <module fileurl="file://${'$'}PROJECT_DIR${'$'}/.idea/user.iml" filepath="${'$'}PROJECT_DIR${'$'}/.idea/user.iml"/>
      </modules></component></project>
    """.trimIndent())
    // MODULE_DIR in an .idea module resolves to the project directory in this IDE.
    // Use the actual shell URL so the fixture cannot accidentally own its parent.
    idea.resolve("shell.iml").writeText(module(shell.toUri().toString().removeSuffix("/")))
    idea.resolve("user.iml").writeText(module(root.resolve("user-content").toUri().toString().removeSuffix("/")))
    select(listOf("repo-a", "repo-b"))
  }

  fun select(ids: List<String>) {
    revision += 1
    val temporary = shell.resolve("reqws-project-${UUID.randomUUID()}.tmp")
    temporary.writeText("""
      {"schemaVersion":1,"adapterProtocol":1,"workspaceId":"ws_fixture","bindingId":"$binding",
       "revision":$revision,"selection":{"mode":"selected","repositoryIds":[${ids.joinToString(",", transform = ::json)}]},
       "updatedAt":"2026-09-21T00:00:00.000Z"}
    """.trimIndent())
    Files.move(temporary, shell.resolve("reqws-project.json"), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
  }

  fun assertDiskPreserved() {
    for (name in listOf("repo-a", "repo-b")) {
      check(Files.isDirectory(root.resolve("$name/.git")))
      check(Files.readString(root.resolve("$name/docs/probe.txt")) == "ordinary text fixture\n")
    }
    check(Files.readString(root.resolve("user-content/keep.txt")) == "user owned\n")
  }

  private fun module(url: String) = """<module type="JAVA_MODULE" version="4"><component name="NewModuleRootManager"><content url="$url"/><orderEntry type="sourceFolder" forTests="false"/></component></module>"""
  private fun json(value: String) = "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
}
