pluginManagement {
    repositories {
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
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        maven("https://build-artifacts.signal.org/libraries/maven") { content { includeGroup("org.signal") } }
    }
}

rootProject.name = "GhostCloak"
include(":app")
include(":identity", ":crypto", ":storage", ":protocol", ":transport", ":test-support", ":backend")
include(":messaging")
include(":attachments")
project(":backend").projectDir = file("../backend")
