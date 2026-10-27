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

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "SQ5Monitor"

// Pure-Kotlin protocol stack (ELM327, ISO-TP, OBD-II, UDS, simulator). Buildable on its own: `./gradlew -p core test`.
includeBuild("core")
include(":app")
