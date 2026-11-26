pluginManagement {
    repositories {
        // Mainland China mirror first; upstream repositories remain as fallbacks.
        // Aliyun is avoided because it answers 502 for some missing artifacts, which fails resolution.
        maven("https://mirrors.cloud.tencent.com/nexus/repository/maven-public/")
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "0.10.0"
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        maven("https://mirrors.cloud.tencent.com/nexus/repository/maven-public/")
        google()
        mavenCentral()
        maven {
            setUrl("https://jitpack.io")
        }
    }
}

rootProject.name = "Curbox"
include(":app")
include(":apitester")
 