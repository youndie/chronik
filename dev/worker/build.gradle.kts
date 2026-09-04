plugins {
    kotlin("jvm")
    application
}


dependencies {
    implementation(project(":chronik-core"))
    implementation(project(":chronik-postgres"))
    implementation(libs.postgres.driver)
}

application {
    mainClass.set("ru.workinprogress.chronik.dev.MainKt")
}
