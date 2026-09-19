package com.reqws.goland.loading

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.reqws.goland.loading.contract.LoadingSnapshotReader
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Files
import java.nio.file.Path

class LoadingSnapshotReaderTest {
  @Rule @JvmField val temporary = TemporaryFolder()
  private val reader = LoadingSnapshotReader()

  @Test fun onlyRequestedMembersRequireIndependentGitDirectories() {
    val shell = fixture()
    val snapshot = reader.read(shell)
    assertEquals(setOf("one"), snapshot.loadedIds)
    assertEquals(listOf("one", "two"), snapshot.manifest.repositories.map { it.repository.catalogRepositoryId })
    val file = shell.resolve("reqws-project.json")
    Files.writeString(file, Files.readString(file).replace("\"selected\"", "\"all\"").replace(",\"repositoryIds\":[\"one\"]", ""))
    assertThrows(Exception::class.java) { reader.read(shell) }
  }

  @Test fun replacementBindingAndDirectoryInvalidateAPreviouslyVerifiedSnapshot() {
    val shell = fixture()
    val snapshot = reader.read(shell)
    val file = shell.resolve("reqws-project.json")
    val original = Files.readString(file)
    Files.writeString(file, original.replace("95dc7c6a", "a5dc7c6a"))
    assertThrows(Exception::class.java) { reader.verifyCurrent(snapshot) }
    Files.writeString(file, original)
    reader.verifyCurrent(snapshot)
    Files.move(shell, shell.resolveSibling("detached"))
    Files.createDirectory(shell)
    Files.writeString(shell.resolve("reqws-project.json"), original)
    assertThrows(Exception::class.java) { reader.verifyCurrent(snapshot) }
  }

  @Test fun workspaceRootIsNeverAnEntryAndForeignBindingIsRejected() {
    val shell = fixture()
    assertThrows(Exception::class.java) { reader.read(shell.parent.parent.parent) }
    val file = shell.resolve("reqws-project.json")
    Files.writeString(file, Files.readString(file).replace("ws_1", "foreign"))
    assertThrows(Exception::class.java) { reader.read(shell) }
  }

  private fun fixture(): Path {
    val root = temporary.newFolder().toPath().toRealPath()
    val shell = root.resolve(".reqws/ide/goland")
    Files.createDirectories(shell)
    Files.createDirectories(root.resolve("one/.git"))
    Files.createDirectory(root.resolve("two"))
    val manifest = JsonObject().apply {
      addProperty("schemaVersion", 1); addProperty("id", "ws_1"); addProperty("name", "fixture")
      addProperty("featureBranch", "feature/test"); addProperty("rootPath", root.toString())
      addProperty("workspaceFilePath", root.resolve("fixture.code-workspace").toString())
      addProperty("createdAt", "2026-09-19T00:00:00Z"); addProperty("updatedAt", "2026-09-19T00:00:00Z")
      add("repositories", JsonArray().apply { for (name in listOf("one", "two")) add(JsonObject().apply {
        addProperty("catalogRepositoryId", name); addProperty("name", name); addProperty("relativePath", name)
        addProperty("url", "https://example.com/$name.git"); addProperty("defaultBranch", "main")
      }) })
    }
    Files.writeString(root.resolve(".reqws/workspace.json"), manifest.toString())
    Files.writeString(shell.resolve("reqws-project.json"), """{"schemaVersion":1,"adapterProtocol":1,"workspaceId":"ws_1","bindingId":"95dc7c6a-0eaa-4c96-824a-e117316a1db3","revision":1,"selection":{"mode":"selected","repositoryIds":["one"]},"updatedAt":"2026-09-19T00:00:00Z"}""")
    return shell
  }
}
