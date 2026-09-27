import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import java.util.Properties
import org.jetbrains.intellij.platform.gradle.IntelliJPlatformType
import org.jetbrains.intellij.platform.gradle.tasks.VerifyPluginTask

// Reuse the settings-pinned IntelliJ plugin, its CLI and failure handling. There
// are deliberately no Kotlin/Compose compilers, production dependencies or tests.
plugins {
  id("org.jetbrains.intellij.platform")
}

require(providers.gradleProperty("reqwsVerificationOnly").orNull == "true")
val compatibilityPolicy = Properties().apply {
  file("compatibility.properties").inputStream().use(::load)
}
fun policy(key: String): String = requireNotNull(compatibilityPolicy.getProperty(key)) { "Missing policy: $key" }
require(policy("minimumPlatformBranch") == "262")
require(policy("compileIdeProduct") == "GO" && policy("verificationProducts") == "GO")
version = providers.gradleProperty("releaseVersion").get()
val candidateArchive = file(providers.gradleProperty("reqwsPluginArchive").get())
val snapshotFile = file(providers.gradleProperty("reqwsVerificationSnapshot").get())
val snapshot = JsonSlurper().parse(snapshotFile) as Map<*, *>
require(snapshot["schemaVersion"] == 1)
require(snapshot["policy"] == compatibilityPolicy.entries.associate { it.key.toString() to it.value.toString() })
val requestedTarget = providers.gradleProperty("reqwsVerificationTarget").get()
val targets = (snapshot["targets"] as List<*>).map { it as Map<*, *> }
require(targets.map { it["id"] }.toSet().size == targets.size) { "Duplicate frozen targets" }
val target = targets.single { it["id"] == requestedTarget }
require(target["product"] == "GO" && target["channel"] == "release" && target["required"] == true)
require(requestedTarget == "GO-${target["build"]}")
require(candidateArchive.isFile && candidateArchive.length() > 0) { "Candidate archive missing" }
val reports = file(providers.gradleProperty("reqwsVerificationReports").get())
val runtimePin = Properties().apply {
  file("verifier-runtime.properties").inputStream().use(::load)
}
fun runtimePolicy(key: String): String = requireNotNull(runtimePin.getProperty(key)) { "Missing runtime pin: $key" }
listOf("compileIdeProduct", "compileIdeVersion", "compileIdeBuild").forEach { key ->
  require(runtimePolicy(key) == policy(key)) { "Compile SDK changed; review the verifier JBR pin before proceeding" }
}
require(System.getProperty("os.name") == "Linux" && System.getProperty("os.arch") in listOf("amd64", "x86_64")) {
  "The reviewed verification-only runtime is Linux x64; other hosts use the retained build path"
}

fun sdkInfo(sdk: java.io.File): Map<*, *> {
  val infoFile = listOf(sdk.resolve("product-info.json"), sdk.resolve("Resources/product-info.json"),
    sdk.resolve("Contents/Resources/product-info.json")).single { it.isFile }
  return JsonSlurper().parse(infoFile) as Map<*, *>
}

dependencies {
  intellijPlatform {
    // VerifyPluginTask has a platform input even for an existing ZIP. Use the
    // very same Maven SDK as the frozen target, not a second compile installer.
    create(IntelliJPlatformType.GoLand, target["version"] as String) {
      useInstaller = false
    }
    // Same release as the baseline bundled JBR, independently downloadable.
    // The doFirst identity gate rejects missing downloads and runtime fallback.
    jetbrainsRuntimeExplicit(runtimePolicy("runtimeArchive"))
    pluginVerifier(policy("pluginVerifierVersion"))
  }
}

intellijPlatform {
  buildSearchableOptions = false
  caching { ides { enabled = true } }
  pluginVerification {
    // Kept identical to the retained build and checked by workflow regressions.
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
      create(IntelliJPlatformType.GoLand, target["version"] as String) {
        useInstaller = false
      }
    }
  }
}

tasks.named<VerifyPluginTask>("verifyPlugin") {
  notCompatibleWithConfigurationCache("Frozen ProductInfo assertions use this run's target snapshot")
  archiveFile.set(candidateArchive)
  verificationReportsDirectory.set(reports)
  systemProperty("plugin.verifier.home.dir", layout.buildDirectory.dir("reports/pluginVerifier/verifier-home").get().asFile)
  offline = false
  doFirst {
    val info = sdkInfo(ides.files.single())
    require(info["productCode"] == target["product"] && info["buildNumber"] == target["build"] &&
      info["version"] == target["version"]) { "Downloaded IDE ProductInfo differs from frozen target" }
    val runtime = runtimeDirectory.get().asFile
    val runtimeRelease = Properties().apply { runtime.resolve("release").inputStream().use(::load) }
    val identity = listOf("JAVA_VERSION", "JAVA_RUNTIME_VERSION", "IMPLEMENTOR", "OS_ARCH").associateWith {
      requireNotNull(runtimeRelease.getProperty(it)) { "Runtime identity is missing: $it" }.removeSurrounding("\"")
    }
    identity.forEach { (key, value) ->
      require(value == runtimePolicy(key)) { "Verifier runtime differs from the reviewed baseline JBR: $key" }
    }
    // Numeric/resource telemetry does not include runtime identity. Record only
    // these public fields, never credentials, environment dumps or command args.
    reports.parentFile.resolve("runtime.json").writeText(JsonOutput.prettyPrint(JsonOutput.toJson(mapOf(
      "schemaVersion" to 1, "mode" to "verification-only", "target" to requestedTarget,
      "compileIdeBuild" to policy("compileIdeBuild"), "runtimeArchive" to runtimePolicy("runtimeArchive"),
      "runtime" to identity,
    ))) + "\n")
    logger.lifecycle("Verification-only runtime: ${JsonOutput.toJson(identity)}")
  }
}
