plugins {
    alias(wip.plugins.kotlinJvm) apply false
    alias(wip.plugins.kotlinMultiplatform) apply false
}

// The JVM floor used to be set here by hand, for every module at once, along with the
// org.gradle.jvm.version attribute that the multiplatform plugin does not stamp on its own.
//
// Both now come from `sborka.kmp` / `sborka.jvm`, driven by `sborka.jvmFloor` in gradle.properties
// — and they come from one place there rather than two, which is the point: the bytecode a module
// is compiled to and the floor its variants advertise are one statement said twice, and a
// repository that got them from different files had the bytecode right and the metadata silent.
// The toolchain is a separate number and stays at sborka's default (25): it is only the JDK that
// runs the compiler and the tests.
