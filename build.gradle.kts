import org.gradle.api.attributes.java.TargetJvmVersion

plugins {
    alias(libs.plugins.kotlinJvm) apply false
    alias(libs.plugins.kotlinMultiplatform) apply false
}

// The JVM floor (JvmFloor.kt) for every module at once.
//
// At once is a Gradle requirement rather than tidiness: a module built below the floor cannot
// depend on one advertising it, so it is all of them or none.
//
// The catch is WHO advertises. The java plugin stamps org.gradle.jvm.version on its variants from
// the toolchain; the multiplatform plugin's jvm() target does not stamp it at all, so a module
// published from jvm() would ship bytecode with nothing in the metadata saying which Java it needs
// — and a consumer below the floor would resolve it, compile against it, and meet
// UnsupportedClassVersionError at class loading. The attribute is therefore set here by hand.
subprojects {
    afterEvaluate {
        (extensions.findByName("kotlin") as? org.jetbrains.kotlin.gradle.dsl.KotlinProjectExtension)
            ?.jvmToolchain(JVM_FLOOR)

        configurations
            .matching { it.name == "jvmApiElements" || it.name == "jvmRuntimeElements" }
            .configureEach {
                attributes {
                    attribute(TargetJvmVersion.TARGET_JVM_VERSION_ATTRIBUTE, JVM_FLOOR)
                }
            }
    }
}
