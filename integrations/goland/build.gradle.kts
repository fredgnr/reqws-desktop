import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.work.DisableCachingByDefault
import org.jetbrains.intellij.platform.gradle.IntelliJPlatformType
import org.jetbrains.intellij.platform.gradle.TestFrameworkType
import org.jetbrains.intellij.platform.gradle.tasks.BuildPluginTask
import org.jetbrains.intellij.platform.gradle.tasks.SignPluginTask
import org.jetbrains.intellij.platform.gradle.tasks.VerifyPluginSignatureTask
import org.jetbrains.intellij.platform.gradle.tasks.ComposedJarTask
import org.jetbrains.intellij.platform.gradle.tasks.VerifyPluginTask
import org.jetbrains.kotlin.gradle.dsl.JvmDefaultMode
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.util.zip.ZipFile
import java.util.Properties
import java.time.Duration
import groovy.json.JsonSlurper

plugins {
  id("org.jetbrains.kotlin.jvm")
  id("org.jetbrains.intellij.platform")
}

@DisableCachingByDefault(because = "Verification task has no outputs")
abstract class VerifyForbiddenProductionSymbolsTask : DefaultTask() {
  @get:InputFiles
  @get:PathSensitive(PathSensitivity.RELATIVE)
  abstract val productionSources: ConfigurableFileCollection

  @get:Internal
  abstract val sourceRoot: DirectoryProperty

  @get:InputFile
  @get:PathSensitive(PathSensitivity.RELATIVE)
  abstract val composedJar: RegularFileProperty

  @get:Input
  abstract val forbiddenSymbols: ListProperty<String>

  @TaskAction
  fun verifySymbols() {
    val findings = sortedSetOf<String>()
    val configuredSymbols = forbiddenSymbols.get()
    val scannerSentinels = listOf(
      "manager.setDirectoryMappings(emptyList())",
      "import com.goide.project.GoProject",
      "Lcom/goide/project/GoProject;",
      "VgoModulesRegistry.getInstance(project)",
      "VgoStatusTracker.getInstance(project).trackModule(module)",
      "ProcessBuilder(\"go\", \"list\")",
    )
    val uncoveredSentinels = scannerSentinels.filter { sentinel ->
      configuredSymbols.none(sentinel::contains)
    }
    if (uncoveredSentinels.isNotEmpty()) {
      throw GradleException(
        "Forbidden-symbol scanner self-test did not reject: ${uncoveredSentinels.joinToString()}",
      )
    }
    val sourceRootDirectory = sourceRoot.get().asFile
    val sourceFiles = productionSources.files
      .filter { it.isFile }
      .sortedBy { it.relativeTo(sourceRootDirectory).invariantSeparatorsPath }

    sourceFiles.forEach { sourceFile ->
      val sourceText = sourceFile.readBytes().toString(Charsets.ISO_8859_1)
      configuredSymbols.forEach { symbol ->
        if (sourceText.contains(symbol)) {
          findings += "src/main/${sourceFile.relativeTo(sourceRootDirectory).invariantSeparatorsPath}: $symbol"
        }
      }
    }

    val composedJarFile = composedJar.get().asFile
    var classEntryCount = 0
    ZipFile(composedJarFile).use { zipFile ->
      zipFile.entries().asSequence()
        .filter { !it.isDirectory && it.name.endsWith(".class") }
        .sortedBy { it.name }
        .forEach { entry ->
          classEntryCount += 1
          val classBytes = zipFile.getInputStream(entry).use { it.readBytes() }
          val classText = classBytes.toString(Charsets.ISO_8859_1)
          configuredSymbols.forEach { symbol ->
            if (classText.contains(symbol)) {
              findings += "${composedJarFile.name}!/${entry.name}: $symbol"
            }
          }
        }
    }

    if (findings.isNotEmpty()) {
      throw GradleException(
        buildString {
          appendLine("Forbidden production symbols detected:")
          findings.forEach { appendLine("- $it") }
        }.trimEnd(),
      )
    }

    logger.lifecycle(
      "Forbidden-symbol audit passed: ${sourceFiles.size} src/main files and $classEntryCount composed JAR classes scanned.",
    )
  }
}

group = "com.reqws.goland"
// CI and tag builds verify the same explicit version; local builds retain their default.
version = providers.gradleProperty("releaseVersion").orElse("0.1.6").get()

val compatibilityPolicy = Properties().apply {
  layout.projectDirectory.file("compatibility.properties").asFile.inputStream().use(::load)
}
fun policy(key: String): String = requireNotNull(compatibilityPolicy.getProperty(key)) { "Missing policy: $key" }
require(policy("minimumPlatformBranch") == "262") { "Only platform branch 262 is approved" }
require(policy("compileIdeProduct") == "GO" && policy("uiTestIdeProduct") == "GO" && policy("verificationProducts") == "GO")
val candidateArchive = providers.gradleProperty("reqwsPluginArchive")
val verificationSnapshot = providers.gradleProperty("reqwsVerificationSnapshot").orNull
val verificationTarget = providers.gradleProperty("reqwsVerificationTarget").orNull
val frozenTargets: List<Map<*, *>> = if (verificationSnapshot != null) {
  val snapshot = JsonSlurper().parse(file(verificationSnapshot)) as Map<*, *>
  require(snapshot["schemaVersion"] == 1 && snapshot["policy"] == compatibilityPolicy.entries.associate { it.key.toString() to it.value.toString() })
  val targets = snapshot["targets"] as List<*>
  require(targets.isNotEmpty()) { "Frozen matrix cannot be empty" }
  targets.map { it as Map<*, *> }.filter { verificationTarget == null || it["id"] == verificationTarget }.also {
    require(it.isNotEmpty()) { "Requested target is absent from the frozen matrix" }
  }
} else emptyList()
require(verificationTarget == null || verificationSnapshot != null)

fun sdkInfo(sdk: java.io.File): Map<*, *> {
  val infoFile = listOf(sdk.resolve("product-info.json"), sdk.resolve("Resources/product-info.json"),
    sdk.resolve("Contents/Resources/product-info.json")).singleOrNull { it.isFile }
    ?: error("SDK must have exactly one product-info.json: $sdk")
  return JsonSlurper().parse(infoFile) as Map<*, *>
}
fun validateSdk(path: String, role: String) {
  val info = sdkInfo(file(path))
  require(info["productCode"] == policy(role + "IdeProduct") && info["version"] == policy(role + "IdeVersion")) {
    "$role SDK must be ${policy(role + "IdeProduct")}-${policy(role + "IdeVersion")}; overrides never change the policy"
  }
  require(info["buildNumber"] == policy(role + "IdeBuild")) { "SDK build differs from the approved role" }
  require((info["minRequiredJavaVersion"] as? Number)?.toInt() == 25) { "Baseline requires Java 25 ProductInfo" }
}

// Optional read-only SDK reuse for local API checks. This never selects a different
// target, affects compilation, copies user settings, or starts an installed IDE.
val verifierSdkPaths = providers.gradleProperty("reqwsVerifierSdkPaths").orNull?.let { value ->
  (JsonSlurper().parseText(value) as List<*>).map { file(it as String) }
} ?: emptyList()
fun verifierSdk(version: String, build: String): java.io.File? {
  val matching = verifierSdkPaths.filter { sdk ->
    val info = sdkInfo(sdk)
    require(info["productCode"] == "GO") { "Verifier SDK overrides must be GoLand" }
    info["version"] == version
  }
  require(matching.size <= 1) { "Ambiguous local verifier SDK for $version" }
  return matching.singleOrNull()?.also {
    require(sdkInfo(it)["buildNumber"] == build) { "Local verifier SDK differs from frozen target $version / $build" }
  }
}

dependencies {
  testImplementation("junit:junit:4.13.2")

  intellijPlatform {
    val localSdk = providers.gradleProperty("reqwsGoLandSdkPath").orNull
    if (localSdk == null) {
      goland(policy("compileIdeVersion"))
    } else {
      validateSdk(localSdk, "compile")
      local(localSdk)
    }
    testFramework(TestFrameworkType.Platform)
    zipSigner("0.1.43")
    pluginVerifier(policy("pluginVerifierVersion"))
  }
}

kotlin {
  jvmToolchain(25)
  compilerOptions {
    jvmTarget = JvmTarget.JVM_25
    // Avoid compatibility stubs for IntelliJ interfaces. Those synthetic overrides can make
    // Plugin Verifier report deprecated/experimental default methods that plugin code never uses.
    jvmDefault = JvmDefaultMode.NO_COMPATIBILITY
  }
}

tasks {
  withType<JavaCompile>().configureEach {
    options.release = 25
  }
}

val releaseNotesVersion = project.version.toString()

intellijPlatform {
  // ReqWS has no Settings configurables. Do not launch an IDE to index absent options
  // during CI packaging; full IDE processes belong to the explicit local harness.
  buildSearchableOptions = false
  caching {
    ides {
      enabled = true
    }
  }
  pluginConfiguration {
    id = "com.reqws.workspace"
    name = "ReqWS"
    version = project.version.toString()
    changeNotes = providers.fileContents(layout.projectDirectory.file("CHANGELOG.md")).asText.map { text ->
      val heading = "## $releaseNotesVersion"
      val sections = text.split(Regex("(?m)^## "))
      val matches = sections.drop(1).filter { it.lineSequence().first().trim() == releaseNotesVersion }
      require(matches.size == 1) { "Expected exactly one changelog section: $heading" }
      val notes = matches.single().substringAfter('\n', "").trim()
      require(notes.isNotBlank() && !Regex("(?i)\\b(TODO|TBD|placeholder)\\b").containsMatchIn(notes)) {
        "Release change notes must be nonempty and complete"
      }
      "<pre>" + notes.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;") + "</pre>"
    }.get()
    ideaVersion {
      sinceBuild = policy("minimumPlatformBranch")
      untilBuild = provider { null }
    }
  }
  pluginVerification {
    failureLevel = listOf(
      VerifyPluginTask.FailureLevel.COMPATIBILITY_PROBLEMS,
      VerifyPluginTask.FailureLevel.INVALID_PLUGIN,
      VerifyPluginTask.FailureLevel.MISSING_DEPENDENCIES,
      VerifyPluginTask.FailureLevel.INTERNAL_API_USAGES,
      VerifyPluginTask.FailureLevel.EXPERIMENTAL_API_USAGES,
      VerifyPluginTask.FailureLevel.OVERRIDE_ONLY_API_USAGES,
      VerifyPluginTask.FailureLevel.NON_EXTENDABLE_API_USAGES,
    )
    ides {
      fun registerTarget(version: String, build: String) {
        val sdk = verifierSdk(version, build)
        if (sdk != null) local(sdk) else create(IntelliJPlatformType.GoLand, version) {
          // API inspection needs the official SDK, not an OS installer or GUI runtime.
          useInstaller = false
        }
      }
      if (frozenTargets.isNotEmpty()) {
        frozenTargets.forEach { target ->
          require(target["product"] == "GO" && target["channel"] == "release" && target["required"] == true)
          registerTarget(target["version"] as String, target["build"] as String)
        }
      } else {
        val localSdk = providers.gradleProperty("reqwsGoLandSdkPath").orNull
        if (localSdk == null) registerTarget(policy("compileIdeVersion"), policy("compileIdeBuild")) else local(localSdk)
        if (policy("uiTestIdeVersion") != policy("compileIdeVersion")) {
          registerTarget(policy("uiTestIdeVersion"), policy("uiTestIdeBuild"))
        }
      }
    }
  }
}

val forbiddenProductionSymbols = listOf(
  "ExcludeUrlOrderEntity",
  "SourceRootOrderEntity",
  "getModuleFilePath",
  "AdditionalLibraryRootsListener",
  "WorkspaceFileIndexEx",
  "ProjectRootEntity",
  "WorkspaceExcludeModelAdapter",
  "ReqwsExcludePlanner",
  "reqws-managed-project-model.json",
  "com.goide",
  "com/goide",
  "VgoModulesRegistry",
  "VgoIntegrationManager",
  "VgoStatusTracker",
  "com.intellij.ide.trustedProjects.TrustedProjectsListener",
  "com/intellij/ide/trustedProjects/TrustedProjectsListener",
  "trackModule",
  "scheduleUpdatingDependenciesOfAllModules",
  "scheduleUpdatingDependencies",
  "updateModules",
  "setDirectoryMappings",
  "setDirectoryMapping",
  "addDirectoryMapping",
  "removeDirectoryMapping",
  "cleanupMappings",
  "ProjectLevelVcsManagerImpl",
  "ModuleVcsDetector",
  "VcsRootProblemNotifier",
  "ApiStatus.Internal",
  "ApiStatus\$Internal",
  "ApiStatus.Experimental",
  "ApiStatus\$Experimental",
  "java.lang.reflect",
  "java/lang/reflect",
  "kotlin.reflect",
  "kotlin/reflect/full",
  "kotlin/reflect/jvm",
  "Class.forName",
  "ProcessBuilder",
  "java/lang/ProcessBuilder",
  "Runtime.getRuntime",
  "GeneralCommandLine",
  "OSProcessHandler",
)

val productionSourceFiles = fileTree("src/main") {
  include("**/*")
}

val composedJarTask = tasks.named<ComposedJarTask>("composedJar")

val verifyForbiddenProductionSymbols by tasks.registering(VerifyForbiddenProductionSymbolsTask::class) {
  group = "verification"
  description = "Rejects Go APIs, VCS mutation, process execution, and private API symbols in production sources and the composed plugin JAR."
  dependsOn(composedJarTask)
  productionSources.from(productionSourceFiles)
  sourceRoot.set(layout.projectDirectory.dir("src/main"))
  composedJar.set(composedJarTask.flatMap { it.archiveFile })
  forbiddenSymbols.set(forbiddenProductionSymbols)
}

tasks.named("check") {
  dependsOn(verifyForbiddenProductionSymbols)
}

tasks.named("buildPlugin") {
  dependsOn(verifyForbiddenProductionSymbols)
}

// Offline verification retains API/dependency checks; CI requires fresh frozen targets.
tasks.named<VerifyPluginTask>("verifyPlugin") {
  notCompatibleWithConfigurationCache("Frozen ProductInfo assertions use this run's target snapshot")
  mustRunAfter("compileIntegrationTestKotlin", "test", "verifyBaselineTestReports")
  if (candidateArchive.isPresent) archiveFile.set(file(candidateArchive.get()))
  // Verifier recreates its reports tree. Keep downloaded dependencies outside it.
  verificationReportsDirectory.set(layout.buildDirectory.dir("reports/pluginVerifier/results"))
  providers.gradleProperty("reqwsVerificationReports").orNull?.let { verificationReportsDirectory.set(file(it)) }
  // A user's shared Verifier cache may contain unrelated Marketplace plugins whose
  // metadata lookup can fail before this candidate is inspected. Keep our cache scoped.
  systemProperty("plugin.verifier.home.dir", layout.buildDirectory.dir("reports/pluginVerifier/verifier-home").get().asFile)
  doFirst {
    if (frozenTargets.isNotEmpty()) {
      val expected = frozenTargets.map { "${it["product"]}-${it["build"]}" }.toSet()
      val actual = ides.files.map { sdk ->
        val info = sdkInfo(sdk)
        "${info["productCode"]}-${info["buildNumber"]}"
      }.toSet()
      require(actual == expected) { "Downloaded IDE ProductInfo differs from frozen targets: $actual / $expected" }
    }
  }
  offline = providers.gradleProperty("reqwsVerifierOffline").map(String::toBoolean).orElse(false)
}


@DisableCachingByDefault(because = "Exports the exact archive provider for the release consumer")
abstract class ExportPluginArchiveTask : DefaultTask() {
  @get:InputFile
  @get:PathSensitive(PathSensitivity.NONE)
  abstract val archive: RegularFileProperty

  @get:OutputFile
  abstract val pathFile: RegularFileProperty

  @TaskAction
  fun exportPath() {
    val candidate = archive.get().asFile
    require(candidate.isFile && candidate.length() > 0) { "Plugin archive is missing" }
    pathFile.get().asFile.apply {
      parentFile.mkdirs()
      writeText(candidate.absolutePath + "\n")
    }
  }
}

val requirePluginSigning = providers.gradleProperty("requirePluginSigning").map {
  require(it == "true" || it == "false") { "requirePluginSigning must be true or false" }
  it.toBoolean()
}.orElse(false)
val signingCertificate = providers.environmentVariable("REQWS_PLUGIN_CERTIFICATE_FILE")
  .map { file(it) }.orElse(file("../../build/jetbrains/reqws-plugin-chain.crt"))
val signingPreflight by tasks.registering(Exec::class) {
  commandLine("python3", "../../scripts/plugin_signing.py", "preflight", "--certificate", signingCertificate.get())
}
val signedPlugin = tasks.named<SignPluginTask>("signPlugin") {
  dependsOn(signingPreflight)
  archiveFile.set(tasks.named<BuildPluginTask>("buildPlugin").flatMap { it.archiveFile })
  certificateChainFile.fileProvider(signingCertificate)
  privateKeyFile.fileProvider(providers.environmentVariable("REQWS_PLUGIN_PRIVATE_KEY_FILE").map { file(it) })
  password.set(providers.environmentVariable("JETBRAINS_PLUGIN_PRIVATE_KEY_PASSWORD"))
  outputs.upToDateWhen { false }
  outputs.cacheIf { false }
}
val checkedSignature = tasks.named<VerifyPluginSignatureTask>("verifyPluginSignature") {
  dependsOn(signedPlugin)
  inputArchiveFile.set(signedPlugin.flatMap { it.signedArchiveFile })
  certificateChainFile.fileProvider(signingCertificate)
  outputs.upToDateWhen { false }
}
if (requirePluginSigning.get()) {
  require(!gradle.startParameter.isConfigurationCacheRequested) {
    "Production signing requires --no-configuration-cache"
  }
}
tasks.register<ExportPluginArchiveTask>("exportPluginArchivePath") {
  if (requirePluginSigning.get()) {
    dependsOn(checkedSignature)
    archive.set(signedPlugin.flatMap { it.signedArchiveFile })
  } else {
    archive.set(tasks.named<BuildPluginTask>("buildPlugin").flatMap { it.archiveFile })
  }
  pathFile.set(layout.buildDirectory.file("release/plugin-archive.txt"))
}

val verifyCompatibilityDescriptor by tasks.registering(Exec::class) {
  dependsOn("patchPluginXml")
  commandLine("python3", "../../scripts/ide_compatibility.py", "descriptor",
    "src/main/resources/META-INF/plugin.xml", "build/tmp/patchPluginXml/plugin.xml")
}
tasks.named("buildPlugin") { dependsOn(verifyCompatibilityDescriptor) }

// Host-side Starter/Driver dependencies never extend production configurations.
val integrationTestSourceSet = sourceSets.create("integrationTest")
dependencies {
  val starterVersion = policy("starterVersion")
  listOf("ide-starter-squashed", "ide-starter-driver", "ide-starter-product-goland").forEach {
    add(integrationTestSourceSet.implementationConfigurationName, "com.jetbrains.intellij.tools:$it:$starterVersion")
  }
  listOf("driver-client", "driver-sdk", "driver-model").forEach {
    add(integrationTestSourceSet.implementationConfigurationName, "com.jetbrains.intellij.driver:$it:$starterVersion")
  }
  add(integrationTestSourceSet.implementationConfigurationName, "org.jetbrains.kotlin:kotlin-stdlib:2.4.0")
  add(integrationTestSourceSet.implementationConfigurationName, "org.junit.jupiter:junit-jupiter:5.11.4")
  add(integrationTestSourceSet.runtimeOnlyConfigurationName, "org.junit.platform:junit-platform-launcher:1.11.4")
  add(integrationTestSourceSet.implementationConfigurationName, "org.kodein.di:kodein-di-jvm:7.26.1")
}
// Starter's optional JUnit listeners kill processes by a shared "ide-tests" path match.
// Local sessions own explicit process handles; never load that global cleanup extension.
configurations.matching { it.name.startsWith("integrationTest") }.configureEach {
  exclude(group = "com.jetbrains.intellij.tools", module = "ide-starter-junit5")
}
val integrationTest by tasks.registering(Test::class) {
  notCompatibleWithConfigurationCache("Each integration run allocates isolated process state and a fresh evidence directory")
  description = "Local-only: runs three complete-IDE scenario groups against an explicit candidate ZIP."
  group = "verification"
  testClassesDirs = integrationTestSourceSet.output.classesDirs
  classpath = integrationTestSourceSet.runtimeClasspath
  useJUnitPlatform()
  maxParallelForks = 1
  failOnNoDiscoveredTests = true
  outputs.upToDateWhen { false }
  outputs.cacheIf { false }
  timeout.set(Duration.ofMinutes(50))
  systemProperty("java.awt.headless", "false")
  systemProperty("reqws.ui.version", policy("uiTestIdeVersion"))
  systemProperty("reqws.ui.build", policy("uiTestIdeBuild"))
  systemProperty("reqws.plugin.version", project.version.toString())
  systemProperty("junit.jupiter.extensions.autodetection.enabled", "false")
  if (providers.gradleProperty("reqwsLocalIdeRunRoot").isPresent) {
    val runRoot = file(providers.gradleProperty("reqwsLocalIdeRunRoot").get())
    reports.junitXml.outputLocation.set(runRoot.resolve("junit"))
    reports.html.outputLocation.set(runRoot.resolve("test-report"))
  }
  doFirst {
    val (runRoot, profile) = requireLocalIdeLauncher()
    require(candidateArchive.isPresent) { "Supply the exact candidate ZIP through scripts/run_local_ide.py run" }
    require(file(candidateArchive.get()).isFile)
    systemProperty("reqws.plugin.archive", file(candidateArchive.get()).absolutePath)
    systemProperty("reqws.plugin.expectedSha256", providers.gradleProperty("reqwsPluginSha256").get())
    systemProperty("reqws.integration.root", runRoot.absolutePath)
    systemProperty("reqws.local.profile", profile.absolutePath)
    systemProperty("user.home", runRoot.resolve("host-home").absolutePath)
  }
}

fun requireLocalIdeLauncher(): Pair<java.io.File, java.io.File> {
  require(listOf("CI", "GITHUB_ACTIONS", "TEAMCITY_VERSION", "JENKINS_URL", "BUILD_BUILDID").none {
    providers.environmentVariable(it).orNull?.lowercase() !in listOf(null, "", "0", "false")
  }) { "Complete IDE startup is local-only. Heavy platform tests remain in CI." }
  val runRoot = file(providers.gradleProperty("reqwsLocalIdeRunRoot").get())
  val profile = file(providers.gradleProperty("reqwsLocalIdeProfile").get())
  require(runRoot.isDirectory && profile.resolve(".reqws-ide-profile.json").isFile)
  require(runRoot.resolve("host-home").mkdirs() || runRoot.resolve("host-home").isDirectory)
  require(providers.environmentVariable("REQWS_LOCAL_IDE_RUN_ROOT").orNull == runRoot.absolutePath &&
    providers.environmentVariable("REQWS_LOCAL_IDE_PROFILE").orNull == profile.absolutePath) {
    "Use scripts/run_local_ide.py to lock the dedicated profile and allocate fresh run state"
  }
  return runRoot to profile
}

val verifyIdeIntegrationReports by tasks.registering(Exec::class) {
  notCompatibleWithConfigurationCache("Local report location is unique to this run")
  mustRunAfter(integrationTest)
  doFirst {
    val (runRoot, _) = requireLocalIdeLauncher()
    commandLine("python3", "../../scripts/check_ide_test_reports.py",
      runRoot.resolve("junit"), runRoot.resolve("run-root.txt"))
  }
}
integrationTest.configure { finalizedBy(verifyIdeIntegrationReports) }
tasks.register("checkIdeIntegration") {
  description = "Local-only integration; invoke with scripts/run_local_ide.py run. Never rebuilds the candidate."
  group = "verification"
  dependsOn(integrationTest, verifyIdeIntegrationReports)
}

tasks.register<JavaExec>("prepareLocalIdeAuthorization") {
  notCompatibleWithConfigurationCache("Interactive local authorization uses a dedicated profile and a fresh run directory")
  description = "Local-only: opens the dedicated GoLand authorization environment without a business project."
  group = "verification"
  classpath = integrationTestSourceSet.runtimeClasspath
  mainClass.set("com.reqws.goland.LocalIdeAuthorization")
  timeout.set(Duration.ofMinutes(50))
  systemProperty("java.awt.headless", "false")
  systemProperty("reqws.ui.version", policy("uiTestIdeVersion"))
  systemProperty("reqws.ui.build", policy("uiTestIdeBuild"))
  doFirst {
    val (runRoot, profile) = requireLocalIdeLauncher()
    systemProperty("reqws.integration.root", runRoot.absolutePath)
    systemProperty("reqws.local.profile", profile.absolutePath)
    systemProperty("user.home", runRoot.resolve("host-home").absolutePath)
  }
}

val verifyBaselineTestReports by tasks.registering(Exec::class) {
  dependsOn("test")
  commandLine("python3", "../../scripts/check_junit_reports.py", "build/test-results/test")
}
