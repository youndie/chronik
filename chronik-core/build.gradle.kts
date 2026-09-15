plugins {
    kotlin("multiplatform")
    alias(libs.plugins.sborkaKmp)
    alias(libs.plugins.sborkaLint)
    alias(libs.plugins.sborkaPublish)
}

kotlin {
    jvm()

    // THE SECOND TARGET, AND THE REASON IS A CONSUMER RATHER THAN A ROADMAP.
    //
    // A Kotlin/Native service cannot take a jvm-only library at all: its build does not fail on a
    // missing feature, it fails on resolution, with "no matching variant". So `linuxX64` here is
    // what makes chronik usable from one, and nothing about the primitive had to change to get it —
    // every line of this module is in `commonMain`, the clock is a parameter rather than a call to
    // the platform (D2 in the research), and nothing reaches for `java.*`.
    //
    // Only linuxX64, and that is a decision rather than a first instalment. It is where a server
    // built on Kotlin/Native runs; Apple and mingw targets would add test tasks nothing runs and
    // klibs nobody has asked for. Adding one later is a line here — the cost of leaving it out is
    // not paid by anybody today.
    linuxX64()

    sourceSets {
        commonMain {
            dependencies {
                // api, not implementation: CoroutineScope and Job appear in the public signature of
                // the worker's start(), so a consumer that only gets coroutines at runtime cannot
                // compile the call.
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
