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
import com.intellij.ide.starter.ide.DefaultIdeDistributionFactory
import com.intellij.ide.starter.ide.IdeInstaller
import com.intellij.ide.starter.ide.InstalledIde
import com.intellij.ide.starter.path.GlobalPaths
import com.intellij.ide.starter.runner.IDERunContext
import com.intellij.platform.testFramework.teamCity.TeamCityReporter.SyntheticTestKind
import org.kodein.di.DI
import org.kodein.di.bindSingleton
import java.awt.GraphicsEnvironment
import java.nio.file.AccessDeniedException
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element

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
    val testCase = TestCase(IdeInfo.GoLand, project).useRelease(requireNotNull(expected["version"]))
    val installers = Files.createDirectories(profile.resolve("installers"))
    return testCase.onIDE(testCase.ideInfo.copy(getInstaller = {
      object : IdeInstaller {
        override suspend fun install(ideInfo: IdeInfo): Pair<String, InstalledIde> {
          val cached = cachedIde(ideInfo)
          if (cached != null) return cached.build to cached
          return StandardInstaller(PublicIdeDownloader(), customInstallersDownloadDirectory = installers).install(ideInfo)
        }
      }
    }))
  }

  private fun cachedIde(ideInfo: IdeInfo): InstalledIde? {
    val dependencies = profile.resolve("dependencies")
    val builds = dependencies.resolve("builds")
    val build = builds.resolve("GO-${requireNotNull(expected["build"])}")
    // Only an absent cache may download. A malformed existing installation must
    // fail, rather than replace an SDK already prepared for local permissions.
    for (path in listOf(dependencies, builds, build)) {
      if (Files.notExists(path, NOFOLLOW_LINKS)) return null
      check(Files.isDirectory(path, NOFOLLOW_LINKS) && path.toRealPath() == path) { "Invalid local SDK cache: $path" }
    }
    val app = build.resolve("GoLand.app")
    val contents = app.resolve("Contents")
    val resources = contents.resolve("Resources")
    for (path in listOf(app, contents, resources, contents.resolve("MacOS"), contents.resolve("jbr"),
      contents.resolve("jbr/Contents"), contents.resolve("jbr/Contents/Home"))) {
      check(Files.isDirectory(path, NOFOLLOW_LINKS) && path.toRealPath() == path) { "Invalid local SDK directory: $path" }
    }
    val productInfo = resources.resolve("product-info.json")
    val buildInfo = resources.resolve("build.txt")
    val plist = contents.resolve("Info.plist")
    val executable = contents.resolve("MacOS/${ideInfo.executableFileName}")
    for (path in listOf(productInfo, buildInfo, plist, executable)) {
      check(Files.isRegularFile(path, NOFOLLOW_LINKS) && path.toRealPath() == path) { "Invalid local SDK file: $path" }
    }
    check(Files.size(plist) <= 1024 * 1024) { "Local SDK Info.plist is too large" }
    val plistParser = DocumentBuilderFactory.newInstance().apply {
      setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true)
      setFeature("http://xml.org/sax/features/external-general-entities", false)
      setFeature("http://xml.org/sax/features/external-parameter-entities", false)
      setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false)
      setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "")
      setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "")
      isXIncludeAware = false
      isExpandEntityReferences = false
    }.newDocumentBuilder()
    val plistRoot = Files.newInputStream(plist).use { plistParser.parse(it).documentElement }
    val executableKeys = plistRoot.getElementsByTagName("key").let { keys ->
      (0 until keys.length).map { keys.item(it) }.onEach {
        check(it.childNodes.length == 1 && it.firstChild.nodeType == org.w3c.dom.Node.TEXT_NODE) {
          "Local SDK plist contains an ambiguous key"
        }
      }.filter { it.textContent == "CFBundleExecutable" }
    }
    val executableKey = executableKeys.singleOrNull()
    val executableValue = executableKey?.let { key ->
      generateSequence(key.nextSibling) { it.nextSibling }.filterIsInstance<Element>().firstOrNull()
    }
    check(plistRoot.tagName == "plist" && executableKey?.parentNode?.nodeName == "dict" &&
      executableKey.parentNode.parentNode == plistRoot && executableValue?.tagName == "string" &&
      executableValue.childNodes.length == 1 && executableValue.firstChild.nodeType == org.w3c.dom.Node.TEXT_NODE &&
      executableValue.textContent == ideInfo.executableFileName) { "Local SDK plist selects an unexpected executable" }
    val info = json.readTree(productInfo.toFile())
    check(info.path("productCode").asText() == expected["product"] &&
      info.path("version").asText() == expected["version"] &&
      info.path("buildNumber").asText() == expected["build"] &&
      Files.readString(buildInfo).trim() == "GO-${expected["build"]}") { "Cached SDK differs from the fixed representative" }
    // The public factory can swap JBR when configured, or restore an old backup.
    // Reject both inputs so reusing a verified SDK cannot mutate its runtime.
    check(System.getProperty("intellij.test.jbr.path").isNullOrEmpty()) { "A local SDK JBR override is not allowed" }
    check(Files.notExists(contents.resolve("jbr/Contents/Home.bundled"), NOFOLLOW_LINKS)) { "A local SDK JBR backup must not be restored" }
    val apps = Files.list(build).use { paths -> paths.filter { it.fileName.toString().endsWith(".app") }.toList() }
    check(apps == listOf(app)) { "The fixed SDK cache must contain exactly GoLand.app" }
    // A fresh descriptor per context avoids sharing mutable Starter VM options.
    return DefaultIdeDistributionFactory.installIDE(build, ideInfo.executableFileName).also {
      check(it.productCode == "GO" && it.build.removePrefix("GO-") == expected["build"] &&
        it.installationPath.toRealPath() == contents) { "Starter resolved a different local SDK" }
    }
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
    if (!preTrustProject) {
      // This bundled, optional linter throws before trust in the fixed IDE.
      // Isolate it only for the ReqWS trust scenario; never mute IDE errors or
      // change the persistent profile's plugin state or the candidate ZIP.
      pluginConfigurator.disablePlugins("com.ypwang.plugin.go-linter")
      val disabled = pluginConfigurator.disabledPluginsPath.toRealPath()
      check(disabled.startsWith(root) && !Files.isSymbolicLink(disabled))
      applyVMOptionsPatch { addSystemProperty("disabled.plugins.file.path", disabled) }
      json.writeValue(root.resolve("trust-scenario-options.json").toFile(), mapOf(
        "disabledPlugins" to listOf("com.ypwang.plugin.go-linter"),
        "reason" to "Bundled Go Linter throws before project trust in GO-262.9437.286",
        "disabledPluginsFile" to disabled.toString(),
      ))
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
