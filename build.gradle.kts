import org.jetbrains.intellij.platform.gradle.IntelliJPlatformType
import org.jetbrains.intellij.platform.gradle.TestFrameworkType

plugins {
    id("java")
    id("org.jetbrains.intellij.platform") version "2.18.1"
}

group = "cn.luckyh"
val pluginVersion = providers.gradleProperty("pluginVersion").get()
val pluginVersionParts = pluginVersion.split('.')
require(pluginVersionParts.size == 3 && pluginVersionParts.all { it.toIntOrNull() != null }) {
    "pluginVersion must use the major.minor.patch format"
}
val requestedTaskNames = gradle.startParameter.taskNames.map { it.substringAfterLast(':') }
val releaseBuildRequested = "buildRelease" in requestedTaskNames
val snapshotBuildRequested = "buildSnapshot" in requestedTaskNames
require(!(releaseBuildRequested && snapshotBuildRequested)) {
    "buildRelease and buildSnapshot cannot run together"
}
version = if (snapshotBuildRequested) "$pluginVersion-SNAPSHOT" else pluginVersion

repositories {
    intellijPlatform {
        defaultRepositories()
    }
    mavenCentral()
}

dependencies {
    intellijPlatform {
        create(IntelliJPlatformType.IntellijIdeaCommunity, "2023.3")
        bundledPlugin("com.intellij.java")
        testFramework(TestFrameworkType.Plugin.Java)
        pluginVerifier()
    }

    compileOnly("org.projectlombok:lombok:1.18.36")
    annotationProcessor("org.projectlombok:lombok:1.18.36")
    implementation("org.yaml:snakeyaml:2.6")
    testImplementation("junit:junit:4.13.2")
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(17)
    }
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.release = 17
}

intellijPlatform {
    pluginConfiguration {
        ideaVersion {
            sinceBuild = "233"
        }
    }

    pluginVerification {
        ides {
            create(IntelliJPlatformType.IntellijIdeaCommunity, "2023.3")
            create(IntelliJPlatformType.IntellijIdea, "2026.2.1")
        }
    }
}

tasks.test {
    useJUnit()
}

tasks.register("buildRelease") {
    group = "build"
    description = "Builds a release plugin and advances the next patch version"
    dependsOn("buildPlugin")
    doLast {
        val nextPluginVersion = "${pluginVersionParts[0]}.${pluginVersionParts[1]}.${pluginVersionParts[2].toInt() + 1}"
        val propertiesFile = layout.projectDirectory.file("gradle.properties").asFile
        val currentProperties = propertiesFile.readText()
        val currentVersionLine = "pluginVersion=$pluginVersion"
        require(currentProperties.contains(currentVersionLine)) {
            "gradle.properties does not contain $currentVersionLine"
        }
        propertiesFile.writeText(currentProperties.replace(currentVersionLine, "pluginVersion=$nextPluginVersion"))
        logger.lifecycle("Release plugin $pluginVersion built; next release version: $nextPluginVersion")
    }
}

tasks.register("buildSnapshot") {
    group = "build"
    description = "Builds a local snapshot plugin without advancing the release version"
    dependsOn("buildPlugin")
    doLast {
        logger.lifecycle("Snapshot plugin $version built")
    }
}
