plugins {
    kotlin("multiplatform")
    alias(libs.plugins.sborkaKmp)
    alias(libs.plugins.sborkaLint)
    alias(libs.plugins.sborkaPublish)
}

kotlin {
    jvm()

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
