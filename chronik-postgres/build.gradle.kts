plugins {
    kotlin("jvm")
    alias(libs.plugins.sborkaJvm)
    alias(libs.plugins.sborkaLint)
    alias(libs.plugins.sborkaPublish)
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

dependencies {
    // The corpus, run against this backend. A test dependency: an application wiring up the store
    // has no use for it, and a library that dragged its own conformance kit onto every consumer's
    // classpath would be shipping its test suite.
    testImplementation(project(":chronik-conformance"))
}
