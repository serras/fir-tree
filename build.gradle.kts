import org.jetbrains.intellij.tasks.RunPluginVerifierTask

plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm") version "1.9.25"
    id("org.jetbrains.intellij") version "1.17.3"
    id("io.gitlab.arturbosch.detekt") version "1.23.6"
}

group = "com.serranofp"
version = "0.3.0"

repositories {
    mavenCentral()
}

dependencies {
    detektPlugins("io.gitlab.arturbosch.detekt:detekt-formatting:1.23.6")
}

// Configure Gradle IntelliJ Plugin
// Read more: https://plugins.jetbrains.com/docs/intellij/tools-gradle-intellij-plugin.html
intellij {
    version.set("2024.1")
    type.set("IC") // Target IDE Platform
    plugins.set(listOf("org.jetbrains.kotlin", "com.intellij.java"))
    downloadSources.set(true)
}

tasks {
    // Set the JVM compatibility versions
    withType<JavaCompile> {
        sourceCompatibility = "17"
        targetCompatibility = "17"
    }

    withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile> {
        kotlinOptions.jvmTarget = "17"
        kotlinOptions {
            compilerOptions.freeCompilerArgs.add("-Xextended-compiler-checks")
        }
    }

    buildSearchableOptions {
        enabled = false
    }

    runPluginVerifier {
        subsystemsToCheck = "without-android"
        failureLevel = RunPluginVerifierTask.FailureLevel.ALL
    }

    patchPluginXml {
        sinceBuild.set("241")
        untilBuild.set("241.*")
    }

    signPlugin {
        certificateChain.set(providers.environmentVariable("CERTIFICATE_CHAIN"))
        privateKey.set(providers.environmentVariable("PRIVATE_KEY"))
        password.set(providers.environmentVariable("PRIVATE_KEY_PASSWORD"))
    }

    publishPlugin {
        token.set(providers.environmentVariable("PUBLISH_TOKEN"))
    }
}
