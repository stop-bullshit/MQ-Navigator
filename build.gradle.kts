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

tasks.processResources {
    from(listOf("LICENSE", "THIRD_PARTY_NOTICES.md")) {
        into("META-INF")
    }
    from("licenses") {
        into("META-INF/licenses")
    }
}

intellijPlatform {
    pluginConfiguration {
        // 插件安装范围覆盖IDEA2023.3至2026.3
        ideaVersion {
            sinceBuild = "233"
            untilBuild = "263.*"
        }
    }

    pluginVerification {
        ides {
            create(IntelliJPlatformType.IntellijIdeaCommunity, "2023.3")
            create(IntelliJPlatformType.IntellijIdea, "2026.2.1")
            // 兼容性检查覆盖IDEA2026.3
            create(IntelliJPlatformType.IntellijIdea, "2026.3")
        }
    }
}

tasks.test {
    useJUnit()
}

tasks.register("buildRelease") {
    group = "build"
    description = "Builds a release plugin and advances the next patch version"
    // 逻辑变动: 本地正式构建先通过测试结构与兼容性检查-20261026-1743-01
    dependsOn("test", "verifyPluginProjectConfiguration", "verifyPluginStructure", "buildPlugin", "verifyPlugin")
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
