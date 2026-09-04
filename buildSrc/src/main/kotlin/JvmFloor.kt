// The oldest Java a consumer of chronik may be on. A decision rather than a side effect of whichever
// JDK the build happened to run on, which is why it is a named constant and not a literal at each
// use site.
//
// It has to reach places that cannot see each other: the toolchain every module compiles with, and
// the org.gradle.jvm.version attribute the published variants advertise. A number kept in several
// places drifts in all but one of them.
//
// 21, and taken from the neighbouring engine rather than from this machine's JDK: chronik is meant
// to be scheduled from inside a consumer's transaction, so it has to be usable by whatever that
// consumer runs on. Raising this floor is a breaking change for them and a one-line diff here,
// which is exactly the asymmetry that makes it worth writing down.
const val JVM_FLOOR = 21
