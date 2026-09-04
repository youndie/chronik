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

// The only module that knows SQL. Owns the table, the selection and the lease; ships no DDL and no
// driver, because choosing those is the application's decision.
include(":chronik-postgres")

// The handover stand's worker. Not published and not part of the library: it exists so that
// `dev/check-handover.sh` has a real process to kill.
include(":dev-worker")
project(":dev-worker").projectDir = file("dev/worker")

// The measurement stand. Not published: it exists to produce the numbers in docs/benchmarking.md,
// and a number without the stand that produced it is not a measurement.
include(":chronik-benchmark")
