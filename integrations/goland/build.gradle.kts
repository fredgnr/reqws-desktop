import org.jetbrains.intellij.platform.gradle.IntelliJPlatformType
import org.jetbrains.intellij.platform.gradle.TestFrameworkType
import org.jetbrains.kotlin.gradle.dsl.JvmDefaultMode
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
  id("org.jetbrains.kotlin.jvm")
  id("org.jetbrains.intellij.platform")
}

group = "com.reqws.goland"
version = "0.1.0"

dependencies {
  testImplementation("junit:junit:4.13.2")

  intellijPlatform {
    goland("2026.1.3")
    bundledPlugin("Git4Idea")
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
