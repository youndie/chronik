plugins {
    kotlin("multiplatform")
    alias(libs.plugins.sborkaKmp)
    alias(libs.plugins.sborkaLint)
    alias(libs.plugins.sborkaPublish)
}

kotlin {
    jvm()

    // The corpus travels with the target list of the primitive it checks, and it has to: a backend
    // written for Kotlin/Native has nothing to be checked WITH if the corpus stops at the JVM, and
    // "the second implementation is verifiable by the same cases" is the whole reason B-12 built it
    // while there was only one.
    linuxX64()

    sourceSets {
        commonMain {
            dependencies {
                // The corpus is built out of chronik's own contracts, and it carries NO test
                // framework: it collects findings and returns them. A kit that threw would stop at
                // the first violation, and a new backend would then be fixed one finding per run.
                api(project(":chronik-core"))
                api(libs.kotlinx.coroutines.core)
            }
        }
        commonTest {
            dependencies {
                implementation(kotlin("test"))
                implementation(libs.kotlinx.coroutines.test)
            }
        }
    }
}
