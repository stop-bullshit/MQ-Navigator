import org.jetbrains.intellij.platform.gradle.IntelliJPlatformType
import org.jetbrains.intellij.platform.gradle.TestFrameworkType

plugins {
    id("java")
    id("org.jetbrains.intellij.platform") version "2.18.1"
}

group = "cn.luckyh"
// 逻辑变动: 按语义化版本发布SVG插件Logo和导航图标修复-20260818-2003-01
version = "0.2.3"

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
