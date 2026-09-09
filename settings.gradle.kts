rootProject.name = "chronik"

enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")

pluginManagement {
    repositories {
        mavenCentral()
        gradlePluginPortal()
        // Written out by hand, and it has to be: `pluginManagement` is evaluated before any settings
        // plugin is applied — including the sborka one, which is fetched through it.
        maven("https://reposilite.kotlin.website/snapshots") {
            name = "wip-snapshots"
            content {
                // One group, and it is the only one there can be. The portfolio's move to
                // `io.github.youndie` is finished: nothing this build resolves is under
                // `ru.workinprogress` any more, and a filter naming a group the server is never asked
                // about reads as a dependency that is still there.
                includeGroupByRegex("io\\.github\\.youndie.*")
            }
        }
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
    // Repositories with content filters, the shared catalog, and the shared publishing contract.
    // Taking these rather than hand-rolling was decided by finding, an hour after writing one by
    // hand, that the shared version already carried the same defect and its fix: a `kotlin("jvm")`
    // module whose publish task reports success and uploads nothing.
    id("io.github.youndie.sborka.settings") version "0.4.0.43"
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

// The corpus of cases every storage implementation has to satisfy. Written while there is only one
// implementation, on purpose: a corpus written after the second describes the intersection of the
// two rather than the contract.
include(":chronik-conformance")
