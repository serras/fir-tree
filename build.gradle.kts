import org.jetbrains.intellij.tasks.RunPluginVerifierTask

plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm") version "1.9.21"
    id("org.jetbrains.intellij") version "1.17.2"
    id("io.gitlab.arturbosch.detekt") version "1.23.5"
}

group = "com.serranofp"
version = "0.2"

repositories {
    mavenCentral()
}

dependencies {
    detektPlugins("io.gitlab.arturbosch.detekt:detekt-formatting:1.23.5")
}

// Configure Gradle IntelliJ Plugin
// Read more: https://plugins.jetbrains.com/docs/intellij/tools-gradle-intellij-plugin.html
intellij {
    version.set("2023.3")
    type.set("IC") // Target IDE Platform
    plugins.set(listOf("org.jetbrains.kotlin"))
}

tasks {
    // Set the JVM compatibility versions
    withType<JavaCompile> {
        sourceCompatibility = "17"
        targetCompatibility = "17"
    }

    withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile> {
        kotlinOptions.jvmTarget = "17"
    }

    buildSearchableOptions {
        enabled = false
    }

    runPluginVerifier {
        subsystemsToCheck = "without-android"
        failureLevel = RunPluginVerifierTask.FailureLevel.ALL
    }

    patchPluginXml {
        sinceBuild.set("233")
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
