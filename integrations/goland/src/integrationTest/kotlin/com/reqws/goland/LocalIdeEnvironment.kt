package com.reqws.goland

import com.fasterxml.jackson.databind.ObjectMapper
import com.intellij.driver.client.Driver
import com.intellij.driver.client.Remote
import com.intellij.driver.client.utility
import com.intellij.ide.starter.community.PublicIdeDownloader
import com.intellij.ide.starter.ide.installer.StandardInstaller
import com.intellij.ide.starter.models.IdeInfo
import com.intellij.ide.starter.models.TestCase
import com.intellij.ide.starter.project.ProjectInfoSpec
import com.intellij.tools.ide.starter.product.goland.GoLand
import com.intellij.ide.starter.ci.CIServer
import com.intellij.ide.starter.ci.NoCIServer
import com.intellij.ide.starter.di.di
import com.intellij.ide.starter.ide.IDETestContext
import com.intellij.ide.starter.path.GlobalPaths
import com.intellij.ide.starter.runner.IDERunContext
import com.intellij.platform.testFramework.teamCity.TeamCityReporter.SyntheticTestKind
import org.kodein.di.DI
import org.kodein.di.bindSingleton
import java.awt.GraphicsEnvironment
import java.nio.file.AccessDeniedException
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList

/** Persistent dedicated authorization config; business state belongs to this run only. */
internal class LocalIdeEnvironment {
  val root: Path = Path.of(requireNotNull(System.getProperty("reqws.integration.root"))).toRealPath()
  private val profile = Path.of(requireNotNull(System.getProperty("reqws.local.profile"))).toRealPath()
  private val config = profile.resolve("config")
  private val json = ObjectMapper()
  private val failures = CopyOnWriteArrayList<String>()
  private var requestedLaunches = 0
  private val expected = mapOf("product" to "GO", "version" to System.getProperty("reqws.ui.version"),
    "build" to System.getProperty("reqws.ui.build"))

  fun prepareHost() {
    check(Path.of(System.getProperty("user.home")).toRealPath() == root.resolve("host-home").toRealPath()) {
      "Starter must use the isolated host home, including its macOS saved-state cleanup"
    }
    if (listOf("CI", "GITHUB_ACTIONS", "TEAMCITY_VERSION", "JENKINS_URL", "BUILD_BUILDID").any {
        System.getenv(it)?.lowercase() !in listOf(null, "", "0", "false")
      }) block("CI_NOT_ALLOWED", "Complete IDE integration is local-only; Heavy platform tests remain in CI.")
    if (System.getenv("REQWS_LOCAL_IDE_RUN_ROOT") != root.toString() ||
      System.getenv("REQWS_LOCAL_IDE_PROFILE") != profile.toString()) {
      block("LOCAL_LAUNCHER_REQUIRED", "Use scripts/run_local_ide.py so the dedicated profile is locked and run state isolated.")
    }
    try {
      check(!GraphicsEnvironment.isHeadless())
      check(GraphicsEnvironment.getLocalGraphicsEnvironment().screenDevices.isNotEmpty())
    } catch (failure: Throwable) {
      block("GRAPHICAL_SESSION_UNAVAILABLE", "A local macOS graphical session is required.", failure)
    }
    val marker = json.readTree(profile.resolve(".reqws-ide-profile.json").toFile())
    check(marker.path("purpose").asText() == "reqws-local-ide-authorization")
    check(marker.path("version").asText() == expected["version"] && marker.path("build").asText() == expected["build"])
    check(Files.isDirectory(config) && !Files.isSymbolicLink(config) && !config.startsWith(root))
    configureStarter()
  }

  private fun configureStarter() {
    // Starter 262's DI setter logs stack frames 2..4 without bounding the slice.
    // Keep a separate setup frame for the short standalone authorization entry.
    di = DI {
      extend(di)
      bindSingleton<GlobalPaths>(overrides = true) {
        object : GlobalPaths(root) {
          override val localCacheDirectory: Path = Files.createDirectories(profile.resolve("dependencies"))
          override val cacheDirForProjects: Path get() = Files.createDirectories(root.resolve("project-cache"))
        }
      }
      bindSingleton<CIServer>(overrides = true) {
        object : CIServer by NoCIServer {
          override fun reportTestFailure(
            testName: String, message: String, details: String, linkToLogs: String?,
            kind: SyntheticTestKind, generifyTestName: Boolean,
          ) {
            failures += "$testName: $message\n$details"
            throw AssertionError(failures.last())
          }
        }
      }
    }
  }

  fun <T : ProjectInfoSpec> testCase(project: T): TestCase<T> {
    val testCase = TestCase(IdeInfo.GoLand, project).withVersion(requireNotNull(expected["version"]))
    val installers = Files.createDirectories(profile.resolve("installers"))
    return testCase.onIDE(testCase.ideInfo.copy(getInstaller = {
      StandardInstaller(PublicIdeDownloader(), customInstallersDownloadDirectory = installers)
    }))
  }

  fun configure(context: IDETestContext, project: Path? = null, preTrustProject: Boolean = true): IDETestContext = context.apply {
    check(paths.configDir.startsWith(root) && paths.systemDir.startsWith(root) && paths.pluginsDir.startsWith(root))
    val sdk = ide.installationPath
    check(sdk.toRealPath().startsWith(profile.resolve("dependencies").toRealPath())) { "SDK escaped the dedicated local download cache" }
    val infoFile = listOf(sdk.resolve("product-info.json"), sdk.resolve("Resources/product-info.json"),
      sdk.resolve("Contents/Resources/product-info.json")).singleOrNull { Files.isRegularFile(it) }
      ?: block("SDK_PRODUCT_INFO_MISSING", "Cannot identify the local representative SDK.")
    val info = json.readTree(infoFile.toFile())
    val actual = mapOf("product" to info.path("productCode").asText(), "version" to info.path("version").asText(),
      "build" to info.path("buildNumber").asText())
    if (actual != expected || ide.productCode != "GO" || ide.build.removePrefix("GO-") != expected["build"]) {
      block("SDK_MISMATCH", "The local IDE must match the fixed GoLand representative; the compilation SDK is independent.")
    }
    json.writeValue(root.resolve("actual-ide.json").toFile(), actual)
    if (project != null) {
      check(project.toRealPath().startsWith(root))
      if (preTrustProject) addProjectToTrustedLocations(project, configPath = config)
    }
    // Reuse the dedicated config in place. Never copy licenses, account data or user settings.
    // Starter's system/plugins/log locations and all .idea state stay under the fresh run root.
    applyVMOptionsPatch {
      addSystemProperty("idea.config.path", config)
      System.getenv("JETBRAINS_LICENSE_SERVER")?.takeIf { it.isNotBlank() }?.let {
        withEnv("JETBRAINS_LICENSE_SERVER", it)
      }
      addSystemProperty("user.language", "en")
      addSystemProperty("user.country", "US")
      addSystemProperty("ide.show.tips.on.startup.default.value", false)
    }
    isReportPublishingEnabled = false
  }

  fun verifyRuntimeDirectories(driver: Driver) {
    val properties = driver.utility<LocalIdeSystemProperties>()
    val actualConfig = Path.of(requireNotNull(properties.getProperty("idea.config.path"))).toRealPath()
    check(actualConfig == config.toRealPath()) { "IDE did not use the dedicated authorization config" }
    for (key in listOf("idea.system.path", "idea.plugins.path")) {
      val value = Path.of(requireNotNull(properties.getProperty(key))).toRealPath()
      check(value.startsWith(root)) { "IDE runtime directory escaped the isolated run: $key" }
    }
  }

  fun configureRun(context: IDERunContext, requireTrustUi: Boolean = false) {
    context.artifactsPublishingEnabled = false
    context.addVMOptionsPatch {
      // Apply after Starter's defaults: the dedicated profile must retain the user's
      // actual consent state, without test flags claiming agreements were accepted.
      listOf("jb.consents.confirmation.enabled", "jb.privacy.policy.text", "jb.privacy.policy.ai.assistant.text",
        "marketplace.eula.reviewed.and.accepted", "writerside.eula.reviewed.and.accepted").forEach(::clearSystemProperty)
      if (requireTrustUi) {
        // The real safe-mode dialog and transition are the subject of this scenario.
        // Do not allow a Starter/default VM flag to make all projects trusted.
        addSystemProperty("idea.trust.all.projects", false)
        addSystemProperty("idea.trust.disabled", false)
        addSystemProperty("idea.trust.headless.disabled", false)
      }
    }
  }

  fun reportPermissionBlock(failure: Throwable) {
    val denied = generateSequence(failure) { it.cause }.take(8).any {
      it is SecurityException || it is AccessDeniedException ||
        listOf("accessibility permission", "permission denied", "not permitted to").any { text ->
          it.message?.contains(text, ignoreCase = true) == true
        }
    }
    if (denied) block("IDE_PERMISSION_DENIED", "The isolated IDE/Driver lacks required local filesystem or graphical automation permission.", failure)
  }

  fun checkIdeErrors() {
    check(failures.isEmpty()) { failures.joinToString("\n") }
  }

  fun block(code: String, message: String, cause: Throwable? = null): Nothing {
    val report = root.resolve("environment-blocked.json")
    if (!Files.exists(report)) json.writeValue(report.toFile(), mapOf("code" to code, "message" to message))
    throw IllegalStateException("Environment blocked [$code]: $message", cause)
  }

  fun recordLaunchRequested() {
    requestedLaunches += 1
    Files.writeString(root.resolve("ide-launch-requested"), "$requestedLaunches\n")
  }

  fun recordPreparationClosed() {
    json.writeValue(root.resolve("authorization-session.json").toFile(), mapOf("status" to "closed", "ide" to expected))
  }
}

@Remote("java.lang.System")
interface LocalIdeSystemProperties {
  fun getProperty(key: String): String?
}
