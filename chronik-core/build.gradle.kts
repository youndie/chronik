plugins {
    kotlin("multiplatform")
    id("chronik.publishing")
}


repositories {
    mavenCentral()
}

kotlin {
    jvm()

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
