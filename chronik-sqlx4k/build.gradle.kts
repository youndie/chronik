plugins {
    kotlin("multiplatform")
    alias(libs.plugins.sborkaKmp)
    alias(libs.plugins.sborkaLint)
    alias(libs.plugins.sborkaPublish)
}

kotlin {
    jvm()

    // The target this module exists for. chronik-postgres is Exposed over JDBC, and JDBC is a JVM
    // interface rather than a protocol: it does not travel here. A Kotlin/Native service that wants
    // durable timers had no store at all until this module.
    linuxX64()

    sourceSets {
        commonMain {
            dependencies {
                api(project(":chronik-core"))

                // `sqlx4k`, NOT `sqlx4k-sqlite`, and this is the load-bearing line of the file.
                //
                // The plain artefact is the database-agnostic half of the library — `Driver`,
                // `Transaction`, `Statement`, `ResultSet` — and that is all this store touches: it
                // is handed a driver the application opened and never opens one. The SQLite part of
                // this module is its SQL, not its dependency.
                //
                // Taking the driver instead would put SQLite's Rust runtime on every consumer, and
                // a Kotlin/Native binary that links two of sqlx4k's drivers does not link at all —
                // they define the same symbols (`duplicate symbol: std::panicking::EMPTY_PANIC`,
                // paid for in a neighbouring repository). A store that carries no driver cannot
                // cause that, whichever one the application brings.
                api(libs.sqlx4k.core)
            }
        }
        commonTest {
            dependencies {
                implementation(libs.kotlinx.coroutines.test)

                // The corpus, and the driver to run it against. Both are the test's: an application
                // wiring up this store already has a driver, and does not want our conformance kit.
                implementation(project(":chronik-conformance"))
                implementation(libs.sqlx4k.sqlite)

                // The databases are files, and the file has to be made before the driver opens it.
                // okio rather than a platform call: this runs on both targets from one source.
                implementation(libs.okio)
            }
        }
    }
}
