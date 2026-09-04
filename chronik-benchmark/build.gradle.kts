plugins {
    kotlin("jvm")
    application
}

repositories {
    mavenCentral()
}

dependencies {
    implementation(project(":chronik-core"))
    implementation(project(":chronik-postgres"))
    implementation(libs.postgres.driver)
    // A pool, and it is not an optimisation of the stand — it is the difference between measuring
    // chronik and measuring TCP. Without one, Exposed opens a connection per transaction: the first
    // run of this benchmark timed 12-22 ms for a claim whose own plan takes 0.07 ms, and reported
    // that dropping the index made no difference, because the index was 0.3% of what was timed.
    implementation(libs.hikari)
}

application {
    mainClass.set("ru.workinprogress.chronik.benchmark.MainKt")
}
