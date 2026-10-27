// The protocol core is a standalone build so it can be developed and tested on any JVM
// without the Android SDK. The Android app consumes it through `includeBuild("core")`.
pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositories {
        mavenCentral()
    }
    versionCatalogs {
        create("libs") {
            from(files("../gradle/libs.versions.toml"))
        }
    }
}

rootProject.name = "core"
