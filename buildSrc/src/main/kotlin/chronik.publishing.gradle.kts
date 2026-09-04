plugins {
    `maven-publish`
}

// The coordinate lives here and nowhere else. A group and a version repeated in every module drift
// in all but one of them, and the one that drifts is the one nobody publishes that day.
group = "io.github.youndie"
version = providers.gradleProperty("chronik.version").getOrElse("0.1.0") + "-SNAPSHOT"

// A PUBLICATION FOR THE PLAIN-JVM MODULES, and it is not boilerplate.
//
// The multiplatform plugin registers its publications itself; `kotlin("jvm")` does not. Without
// this block `publishToMavenLocal` on such a module runs, reports success and publishes nothing —
// a green task with no artefact behind it, which is the worst shape a build step can have. Found by
// looking in ~/.m2 rather than by reading the log.
afterEvaluate {
    if (components.findByName("java") != null && publishing.publications.isEmpty()) {
        publishing.publications.create<MavenPublication>("maven") {
            from(components["java"])
        }
    }
}

publishing {
    repositories {
        // mavenLocal only, deliberately. Publishing anywhere else is a decision with a release
        // process behind it, and this repository has neither yet; what it does have is a consumer
        // in another repository that needs to compile against it today.
        mavenLocal()
    }
}
