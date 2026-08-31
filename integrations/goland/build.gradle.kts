import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.work.DisableCachingByDefault
import org.jetbrains.intellij.platform.gradle.IntelliJPlatformType
import org.jetbrains.intellij.platform.gradle.TestFrameworkType
import org.jetbrains.intellij.platform.gradle.tasks.ComposedJarTask
import org.jetbrains.kotlin.gradle.dsl.JvmDefaultMode
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.util.zip.ZipFile

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
version = "0.1.0"

dependencies {
  testImplementation("junit:junit:4.13.2")

  intellijPlatform {
    goland("2026.1.3")
    bundledPlugin("org.jetbrains.plugins.go")
    testFramework(TestFrameworkType.Platform)
  }
}

kotlin {
  jvmToolchain(21)
  compilerOptions {
    jvmTarget = JvmTarget.JVM_21
    // Avoid compatibility stubs for IntelliJ interfaces. Those synthetic overrides can make
    // Plugin Verifier report deprecated/experimental default methods that plugin code never uses.
    jvmDefault = JvmDefaultMode.NO_COMPATIBILITY
  }
}

tasks {
  withType<JavaCompile>().configureEach {
    options.release = 21
  }
}

intellijPlatform {
  pluginConfiguration {
    id = "com.reqws.workspace"
    name = "ReqWS"
    version = project.version.toString()
    ideaVersion {
      sinceBuild = "261"
    }
  }
  pluginVerification {
    ides {
      create(IntelliJPlatformType.GoLand, "2026.1.3")
      create(IntelliJPlatformType.GoLand, "2026.2")
    }
  }
}

val forbiddenProductionSymbols = listOf(
  "VgoIntegrationManager",
  "VgoStatusTracker",
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
  description = "Rejects forbidden Go integration and VCS mapping symbols in production sources and the composed plugin JAR."
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
