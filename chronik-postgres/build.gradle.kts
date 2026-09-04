plugins {
    kotlin("jvm")
}

group = "io.github.youndie"

repositories {
    mavenCentral()
}

dependencies {
    // api: the store this module implements is chronik's, and its types are in the signatures a
    // consumer wires up. implementation would leave them off the compile classpath and the wiring
    // would not compile.
    api(project(":chronik-core"))
    api(libs.exposed.core)
    api(libs.exposed.jdbc)

    // No driver and no connection pool are declared for production on purpose: this module takes an
    // Exposed Database it is handed and does not know which server sits underneath. The driver
    // below is the test's, not the library's.
    testImplementation(kotlin("test"))
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.testcontainers.postgresql)
    testImplementation(libs.postgres.driver)
}

tasks.test {
    useJUnitPlatform()
}
