package com.reqws.goland

import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.transform.TransformerFactory
import javax.xml.transform.dom.DOMSource
import javax.xml.transform.stream.StreamResult

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
  val userCoveredRepositories: Set<String> get() = emptySet()
  val hasManagedUserRoot: Boolean get() = false
  val hasLateFiles: Boolean get() = false
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
  // A unique native project name makes the real Recent Projects UI unambiguous
  // even when the dedicated profile has opened the other scenario shells.
  Files.writeString(idea.resolve(".name"), "ReqWS fixture ${fixture.bindingId}")
  Files.writeString(idea.resolve("modules.xml"), """
    <project version="4"><component name="ProjectModuleManager"><modules>
    <module fileurl="file://${'$'}PROJECT_DIR${'$'}/.idea/shell.iml" filepath="${'$'}PROJECT_DIR${'$'}/.idea/shell.iml"/>
    <module fileurl="file://${'$'}PROJECT_DIR${'$'}/.idea/user.iml" filepath="${'$'}PROJECT_DIR${'$'}/.idea/user.iml"/>
    </modules></component></project>
  """.trimIndent())
  fun module(paths: List<Path>) = """<module type="JAVA_MODULE" version="4"><component name="NewModuleRootManager">${paths.joinToString("") { """<content url="${it.toUri().toString().removeSuffix("/")}"/>""" }}<orderEntry type="sourceFolder" forTests="false"/></component></module>"""
  Files.writeString(idea.resolve("shell.iml"), module(listOf(fixture.shell)))
  Files.writeString(idea.resolve("user.iml"), module(listOf(fixture.root.resolve("user-content")) + fixture.userCoveredRepositories.sorted().map(fixture.root::resolve)))
}

/** User fixture edit after a verified complete IDE exit; never seed a managed claim or marker. */
internal fun addUnclaimedRootToSavedManagedModule(fixture: DesktopProjectionFixture) {
  check(fixture.name == "selection" && fixture.selected.isEmpty() && !fixture.hasManagedUserRoot)
  fixture.verifyInputs()
  val file = fixture.shell.resolve(".idea/reqws/ReqWS-${fixture.bindingId}.iml")
  check(Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) && file.toRealPath() == file)
  val factory = DocumentBuilderFactory.newInstance().apply {
    setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
    setFeature("http://xml.org/sax/features/external-general-entities", false)
    setFeature("http://xml.org/sax/features/external-parameter-entities", false)
  }
  val document = factory.newDocumentBuilder().parse(file.toFile())
  val components = document.getElementsByTagName("component")
  val manager = (0 until components.length).map { components.item(it) as org.w3c.dom.Element }
    .single { it.getAttribute("name") == "NewModuleRootManager" }
  check(manager.getElementsByTagName("content").length == 0) { "Expected the saved explicit empty managed selection" }
  manager.appendChild(document.createElement("content").apply {
    setAttribute("url", fixture.root.resolve("user-extra").toUri().toString().removeSuffix("/"))
  })
  TransformerFactory.newInstance().newTransformer().transform(DOMSource(document), StreamResult(file.toFile()))
  fixture.markManagedUserRoot()
}
