rootProject.name = "chronik"

enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")

pluginManagement {
    repositories {
        mavenCentral()
        gradlePluginPortal()
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

dependencyResolutionManagement {
    repositories {
        mavenCentral()
    }
}

// The primitive: the three operations, the contracts of storage and of the sink, the worker.
// Knows no SQL and executes nobody's code — only the fact "it is time" leaves it.
include(":chronik-core")
