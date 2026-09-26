package com.reqws.goland

import com.fasterxml.jackson.core.JsonParser
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.BasicFileAttributes
import java.util.UUID
import java.util.concurrent.TimeUnit

/** Host-only protocol. Commands contain fixed fixture names, never paths or product APIs. */
internal class DesktopLink(private val runRoot: Path) {
  private val json = ObjectMapper().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
  private val directory = runRoot.resolve("desktop-link")
  private val sessionId = requireNotNull(System.getProperty("reqws.desktop.session"))
  private val identities = linkedMapOf<Path, String>()
  private var sequence = 0
  private val fixtures = mutableMapOf<String, DesktopProjectionFixture>()

  init {
    require(UUID.fromString(sessionId).toString() == sessionId)
    check(System.getenv("REQWS_DESKTOP_IDE_SESSION") == sessionId)
    pinDirectory(runRoot)
    pinDirectory(directory)
    val session = readJson(directory.resolve("session.json"))
    require(session.fieldNames().asSequence().toSet() == setOf("schemaVersion", "purpose", "sessionId"))
    require(session.path("schemaVersion").isInt && session.path("schemaVersion").intValue() == 1)
    require(session.path("purpose").textValue() == "reqws-desktop-ide-link")
    require(session.path("sessionId").textValue() == sessionId)
  }

  fun create(name: String, userModel: Boolean = true): DesktopProjectionFixture {
    require(name in setOf("selection", "trust", "invalid-binding", "invalid-manifest", "coverage") && name !in fixtures)
    val response = exchange(mapOf("operation" to "create", "name" to name))
    val fixture = DesktopProjectionFixture(this, name, response, userModel)
    require(fixture.selected == setOf("repo-a", "repo-b") && fixture.revision == 1L)
    fixtures[name] = fixture
    fixture.verifyInputs()
    fixture.assertDiskPreserved()
    if (userModel) seedUserModel(fixture)
    return fixture
  }

  fun select(fixture: DesktopProjectionFixture, selected: Set<String>) {
    require(fixtures[fixture.name] === fixture && selected.all { it in setOf("repo-a", "repo-b") })
    fixture.verifyInputs()
    val response = exchange(mapOf("operation" to "select", "name" to fixture.name, "selected" to selected.sorted()))
    fixture.acceptSelection(response, selected)
    fixture.verifyInputs()
    fixture.assertDiskPreserved()
  }

  fun checkAbort() {
    verifyDirectories()
    if (Files.exists(directory.resolve("abort.json"), LinkOption.NOFOLLOW_LINKS)) {
      // Do not print arbitrary child output or private host paths into public diagnostics.
      readJson(directory.resolve("abort.json"))
      error("Desktop orchestration aborted; inspect the private run report")
    }
  }

  private fun exchange(command: Map<String, Any>): JsonNode {
    checkAbort()
    sequence += 1
    val request = directory.resolve("request-$sequence.json")
    val response = directory.resolve("response-$sequence.json")
    check(!Files.exists(request, LinkOption.NOFOLLOW_LINKS) && !Files.exists(response, LinkOption.NOFOLLOW_LINKS))
    val value = mapOf("schemaVersion" to 1, "sessionId" to sessionId, "sequence" to sequence) + command
    publish(request, json.writeValueAsBytes(value))
    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(120)
    while (!Files.exists(response, LinkOption.NOFOLLOW_LINKS)) {
      checkAbort()
      check(System.nanoTime() < deadline) { "Desktop request $sequence timed out" }
      Thread.sleep(100)
    }
    checkAbort()
    val answer = readJson(response)
    require(answer.path("schemaVersion").isInt && answer.path("schemaVersion").intValue() == 1)
    require(answer.path("sessionId").textValue() == sessionId)
    require(answer.path("sequence").isInt && answer.path("sequence").intValue() == sequence)
    check(answer.path("status").textValue() == "passed") { "Desktop request $sequence failed; inspect the private response" }
    require(answer.fieldNames().asSequence().toSet() == setOf("schemaVersion", "sessionId", "sequence", "status", "snapshot"))
    return answer.path("snapshot").also { require(it.isObject) }
  }

  private fun publish(path: Path, bytes: ByteArray) {
    verifyDirectories()
    val temporary = directory.resolve(".${path.fileName}-${UUID.randomUUID()}.tmp")
    Files.write(temporary, bytes, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)
    try {
      // A hard-link publication is atomic and fails if the response/request name exists;
      // ATOMIC_MOVE alone may replace an existing target on Unix.
      Files.createLink(path, temporary)
    } finally {
      Files.delete(temporary)
    }
  }

  fun validateWorkspace(root: Path, shell: Path, name: String) {
    require(root.isAbsolute && root == root.normalize() && root.startsWith(runRoot))
    require(root.fileName.toString() == name && root.parent.fileName.toString() == "workspaces")
    require(root.parent.parent.parent == runRoot && root.parent.parent.fileName.toString().startsWith("reqws-e2e-"))
    require(shell == root.resolve(".reqws/ide/goland"))
    var current = root.parent.parent
    pinDirectory(current)
    pinDirectory(current.resolve("output"))
    for (part in current.relativize(shell)) {
      current = current.resolve(part)
      pinDirectory(current)
    }
    for (relative in listOf("repo-a", "repo-a/.git", "repo-a/docs", "repo-b", "repo-b/.git", "repo-b/docs",
      "repo-c", "repo-c/.git", "repo-c/docs", "user-content", "user-extra", "notes")) {
      pinDirectory(root.resolve(relative))
    }
  }

  private fun pinDirectory(path: Path) {
    val key = checkedAttributes(path).also { require(it.isDirectory) }.fileKey()?.toString()
    requireNotNull(key)
    val previous = identities.putIfAbsent(path, key)
    check(previous == null || previous == key) { "A fixture directory was replaced" }
  }

  fun verifyDirectories() {
    identities.forEach { (path, key) ->
      val attributes = checkedAttributes(path)
      check(attributes.isDirectory && attributes.fileKey()?.toString() == key) { "A fixture directory identity changed" }
    }
  }

  fun readBytes(path: Path, maximum: Long = 1024 * 1024): ByteArray {
    verifyDirectories()
    val attributes = checkedAttributes(path)
    require(attributes.isRegularFile && attributes.size() in 1..maximum)
    val bytes = FileChannel.open(path, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS).use { channel ->
      require(channel.size() == attributes.size())
      val buffer = ByteBuffer.allocate(attributes.size().toInt())
      while (buffer.hasRemaining()) check(channel.read(buffer) >= 0) { "Fixture input was truncated while reading" }
      require(channel.size() == attributes.size())
      buffer.array()
    }
    val after = checkedAttributes(path)
    require(after.fileKey() == attributes.fileKey() && after.size() == bytes.size.toLong()) { "Fixture input changed while reading" }
    return bytes
  }

  fun readJson(path: Path): JsonNode = json.readTree(readBytes(path, 64 * 1024)).also { require(it.isObject) }

  fun checkedAttributes(path: Path): BasicFileAttributes {
    require(path.isAbsolute && path == path.normalize() && path.startsWith(runRoot))
    require(path.toRealPath() == path) { "Fixture path traverses a symbolic link" }
    return Files.readAttributes(path, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS).also {
      require(!it.isSymbolicLink)
    }
  }

  fun faultWrite(path: Path, bytes: ByteArray, restoring: Boolean = false) {
    // Fault injection owns only one already verified fixture input, never user data.
    if (restoring) verifyDirectories() else checkAbort()
    require(fixtures.values.any { path == it.shell.resolve("reqws-project.json") || path == it.root.resolve(".reqws/workspace.json") })
    readBytes(path)
    val temporary = path.resolveSibling(".${path.fileName}-${UUID.randomUUID()}.fault")
    Files.write(temporary, bytes, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)
    verifyDirectories()
    checkedAttributes(path)
    Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
  }
}

internal class DesktopProjectionFixture(
  private val link: DesktopLink,
  val name: String,
  snapshot: JsonNode,
  override val hasUserModel: Boolean,
) : ProjectionFixture {
  override val root: Path = Path.of(requiredText(snapshot, "root"))
  private val editorLaunch: JsonNode = snapshot.path("editorLaunch")
  // Use the real EditorLauncher OS target supplied by Desktop, then validate it
  // against the sole legal binding entry before Starter consumes this property.
  override val shell: Path = Path.of(requireNotNull(editorLaunch.path("args").path(2).textValue()))
  override val workspaceId: String = requiredText(snapshot, "workspaceId")
  override val bindingId: String = requiredText(snapshot, "bindingId")
  override val repositories: Map<String, String> = repositoryMap(snapshot)
  override var revision: Long = requiredRevision(snapshot)
    private set
  override var selected: Set<String> = selection(snapshot)
    private set
  override val userCoveredRepositories: Set<String> = if (name == "coverage") setOf("repo-c") else emptySet()
  override var hasManagedUserRoot = false
    private set
  override var hasLateFiles = false
    private set

  fun markManagedUserRoot() { hasManagedUserRoot = true }

  fun createLateFiles() {
    require(name == "selection" && !hasLateFiles)
    verifyInputs()
    Files.writeString(root.resolve("repo-a/docs/late-repo.txt"), "late repository file\n", StandardOpenOption.CREATE_NEW)
    Files.writeString(shell.resolve("late-shell.txt"), "late shell remains hidden\n", StandardOpenOption.CREATE_NEW)
    hasLateFiles = true
  }

  init {
    require(snapshot.fieldNames().asSequence().toSet() == setOf("name", "root", "shell", "workspaceId", "bindingId", "revision", "selected", "repositories", "editorLaunch"))
    require(editorLaunch.isObject && editorLaunch.fieldNames().asSequence().toSet() == setOf("command", "args", "shell", "boundary"))
    require(requiredText(editorLaunch, "command") == "/usr/bin/open" && requiredText(editorLaunch, "boundary") == "os-spawn-only")
    require(editorLaunch.path("shell").isBoolean && !editorLaunch.path("shell").booleanValue())
    require(stringList(editorLaunch.path("args")) == listOf("-a", root.parent.parent.resolve("home/Applications/GoLand.app").toString(), root.resolve(".reqws/ide/goland").toString()))
    require(requiredText(snapshot, "shell") == shell.toString())
    require(requiredText(snapshot, "name") == name)
    require(UUID.fromString(bindingId).toString() == bindingId)
    link.validateWorkspace(root, shell, name)
  }

  fun acceptSelection(snapshot: JsonNode, expected: Set<String>) {
    require(snapshot.fieldNames().asSequence().toSet() == setOf("name", "root", "shell", "workspaceId", "bindingId", "revision", "selected", "repositories", "editorLaunch"))
    require(snapshot.path("editorLaunch") == editorLaunch)
    require(requiredText(snapshot, "name") == name && requiredText(snapshot, "root") == root.toString())
    require(requiredText(snapshot, "shell") == shell.toString() && requiredText(snapshot, "workspaceId") == workspaceId)
    require(requiredText(snapshot, "bindingId") == bindingId && repositoryMap(snapshot) == repositories)
    require(requiredRevision(snapshot) == revision + 1 && selection(snapshot) == expected)
    revision = requiredRevision(snapshot)
    selected = expected.toSet()
  }

  override fun verifyInputs() {
    link.checkAbort()
    val bindingBytes = link.readBytes(shell.resolve("reqws-project.json"))
    val manifestBytes = link.readBytes(root.resolve(".reqws/workspace.json"))
    val json = ObjectMapper().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
    val binding = json.readTree(bindingBytes)
    val manifest = json.readTree(manifestBytes)
    require(binding.path("schemaVersion").isInt && binding.path("schemaVersion").intValue() == 1)
    require(binding.path("adapterProtocol").isInt && binding.path("adapterProtocol").intValue() == 1)
    require(requiredText(binding, "workspaceId") == workspaceId && requiredText(binding, "bindingId") == bindingId)
    require(requiredRevision(binding) == revision && binding.path("selection").path("mode").textValue() == "selected")
    val ids = stringList(binding.path("selection").path("repositoryIds"))
    require(ids.toSet() == selected.map { repositories.getValue(it) }.toSet())
    require(manifest.path("schemaVersion").isInt && manifest.path("schemaVersion").intValue() == 1)
    require(requiredText(manifest, "id") == workspaceId)
    require(requiredText(manifest, "rootPath") == root.toString())
    val workspaceFile = Path.of(requiredText(manifest, "workspaceFilePath"))
    require(workspaceFile == root.parent.parent.resolve("output/$name.code-workspace"))
    require(link.checkedAttributes(workspaceFile).isRegularFile)
    val members = manifest.path("repositories")
    require(members.isArray && members.size() == 3)
    val actual = members.associate { member ->
      val memberName = requiredText(member, "name")
      require(requiredText(member, "relativePath") == memberName)
      memberName to requiredText(member, "catalogRepositoryId")
    }
    require(actual == repositories)
    check(bindingBytes.contentEquals(link.readBytes(shell.resolve("reqws-project.json"))) &&
      manifestBytes.contentEquals(link.readBytes(root.resolve(".reqws/workspace.json")))) { "Desktop inputs changed during verification" }
  }

  override fun assertDiskPreserved() {
    for (name in repositories.keys) {
      require(link.checkedAttributes(root.resolve(name)).isDirectory)
      require(link.checkedAttributes(root.resolve("$name/.git")).isDirectory)
      check(String(link.readBytes(root.resolve("$name/docs/probe.txt"))) == "ordinary text fixture\n")
      check(String(link.readBytes(root.resolve("$name/README.txt"))) == "ReqWS Git fixture: $name\n")
    }
    check(String(link.readBytes(root.resolve("user-content/keep.txt"))) == "user owned\n")
    check(String(link.readBytes(root.resolve("user-extra/keep-extra.txt"))) == "unclaimed root in the managed module\n")
    check(String(link.readBytes(root.resolve("notes/outside.txt"))) == "outside all project roots\n")
    check(String(link.readBytes(shell.resolve("shell-probe.txt"))) == "dedicated shell stays hidden\n")
    if (hasLateFiles) {
      check(String(link.readBytes(root.resolve("repo-a/docs/late-repo.txt"))) == "late repository file\n")
      check(String(link.readBytes(shell.resolve("late-shell.txt"))) == "late shell remains hidden\n")
    }
  }

  private fun repositoryMap(node: JsonNode): Map<String, String> {
    val values = node.path("repositories")
    require(values.isArray && values.size() == 3)
    return values.associate {
      require(it.isObject && it.fieldNames().asSequence().toSet() == setOf("name", "id"))
      requiredText(it, "name") to requiredText(it, "id")
    }.also {
      require(it.keys == setOf("repo-a", "repo-b", "repo-c") && it.values.toSet().size == 3)
    }
  }

  private fun selection(node: JsonNode): Set<String> = stringList(node.path("selected")).toSet().also {
    require(it.all { name -> name in setOf("repo-a", "repo-b") })
  }

  private fun stringList(node: JsonNode): List<String> {
    require(node.isArray)
    return node.map { require(it.isTextual); it.textValue() }.also { require(it.toSet().size == it.size) }
  }

  private fun requiredRevision(node: JsonNode): Long = node.path("revision").let {
    require(it.isIntegralNumber && it.canConvertToLong() && it.longValue() > 0)
    it.longValue()
  }

  private fun requiredText(node: JsonNode, field: String): String = node.path(field).let {
    require(it.isTextual && it.textValue().isNotBlank()) { "Missing fixture field: $field" }
    it.textValue()
  }
}
