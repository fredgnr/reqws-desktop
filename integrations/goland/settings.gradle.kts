import org.jetbrains.intellij.platform.gradle.extensions.intellijPlatform

pluginManagement {
  repositories {
    gradlePluginPortal()
    mavenCentral()
  }
  plugins {
    val kotlinVersion = "2.3.20"
    id("org.jetbrains.kotlin.jvm") version kotlinVersion
    id("org.jetbrains.kotlin.plugin.compose") version kotlinVersion
  }
}

plugins {
  id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
  id("org.jetbrains.intellij.platform.settings") version "2.18.1"
}

dependencyResolutionManagement {
  repositories {
    mavenCentral()
    intellijPlatform {
      defaultRepositories()
    }
  }
}

rootProject.name = "reqws-goland"

// Only the immutable-candidate API consumer may select the lightweight script.
// Normal builds, baseline gates, platform tests and signing retain build.gradle.kts.
val verificationOnly = providers.gradleProperty("reqwsVerificationOnly").orNull
require(verificationOnly == null || verificationOnly == "true") {
  "reqwsVerificationOnly must be absent or true"
}
if (verificationOnly == "true") {
  require(gradle.startParameter.taskNames == listOf("verifyPlugin")) {
    "Verification-only mode cannot execute build, test, signing or IDE tasks"
  }
  listOf("reqwsPluginArchive", "reqwsVerificationSnapshot", "reqwsVerificationTarget",
    "reqwsVerificationReports", "releaseVersion").forEach { key ->
    require(!providers.gradleProperty(key).orNull.isNullOrBlank()) { "Missing verification input: $key" }
  }
  rootProject.buildFileName = "verify.gradle.kts"
}
