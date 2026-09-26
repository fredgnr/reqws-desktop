package com.reqws.goland

import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest

/** Read-only view shared by the synthetic legacy fixture and the real Desktop consumer. */
internal interface ProjectionFixture {
  val root: Path
  val shell: Path
  val workspaceId: String
  val bindingId: String
  val revision: Long
  val repositories: Map<String, String>
  val selected: Set<String>
  val hasUserModel: Boolean get() = true
  fun verifyInputs()
  fun assertDiskPreserved()
}

internal fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes)
  .joinToString("") { "%02x".format(it.toInt() and 0xff) }

/** Independent host calculation of the existing roots-v1 identity, never a product hook. */
internal fun ProjectionFixture.expectedProjectionDigest(): String {
  verifyInputs()
  val manifest = Files.readAllBytes(root.resolve(".reqws/workspace.json"))
  val binding = Files.readAllBytes(shell.resolve("reqws-project.json"))
  val identities = listOf(root, shell.parent.parent, shell.parent, shell).map {
    val attributes = Files.readAttributes(it, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
    check(attributes.isDirectory && !attributes.isSymbolicLink && it.toRealPath() == it)
    requireNotNull(attributes.fileKey()).toString()
  }
  return sha256(binding + (sha256(manifest) + ":roots-v1:" + identities.joinToString()).toByteArray())
}

/** Native, non-ReqWS project state for preservation assertions, allocated only before startup. */
internal fun seedUserModel(fixture: ProjectionFixture) {
  val idea = fixture.shell.resolve(".idea")
  check(!Files.exists(idea, LinkOption.NOFOLLOW_LINKS)) { "Refuse to replace existing IDE metadata" }
  Files.createDirectory(idea)
  Files.writeString(idea.resolve("modules.xml"), """
    <project version="4"><component name="ProjectModuleManager"><modules>
    <module fileurl="file://${'$'}PROJECT_DIR${'$'}/.idea/shell.iml" filepath="${'$'}PROJECT_DIR${'$'}/.idea/shell.iml"/>
    <module fileurl="file://${'$'}PROJECT_DIR${'$'}/.idea/user.iml" filepath="${'$'}PROJECT_DIR${'$'}/.idea/user.iml"/>
    </modules></component></project>
  """.trimIndent())
  fun module(path: Path) = """<module type="JAVA_MODULE" version="4"><component name="NewModuleRootManager"><content url="${path.toUri().toString().removeSuffix("/")}"/><orderEntry type="sourceFolder" forTests="false"/></component></module>"""
  Files.writeString(idea.resolve("shell.iml"), module(fixture.shell))
  Files.writeString(idea.resolve("user.iml"), module(fixture.root.resolve("user-content")))
}
