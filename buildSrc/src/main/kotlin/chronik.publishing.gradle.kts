plugins {
    `maven-publish`
}

// The coordinate lives here and nowhere else. A group and a version repeated in every module drift
// in all but one of them, and the one that drifts is the one nobody publishes that day.
group = "io.github.youndie"
version = providers.gradleProperty("chronik.version").getOrElse("0.1.0") + "-SNAPSHOT"

publishing {
    repositories {
        // mavenLocal only, deliberately. Publishing anywhere else is a decision with a release
        // process behind it, and this repository has neither yet; what it does have is a consumer
        // in another repository that needs to compile against it today.
        mavenLocal()
    }
}
