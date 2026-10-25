import org.gradle.api.tasks.testing.logging.TestExceptionFormat

plugins {
    `java-library`
    `maven-publish`
}

group = "com.botwithus"
// Releases pass -PreleaseVersion (the publish workflow takes it from the tag).
// The default is never published: a static repository has no SNAPSHOT story.
version = (findProperty("releaseVersion") as String?) ?: "0.1.0-SNAPSHOT"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
    withSourcesJar()
    withJavadocJar()
}

repositories {
    mavenCentral()
}

dependencies {
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.msgpack.core)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.compilerArgs.addAll(listOf("-Xlint:all", "-Werror", "-parameters"))
}

tasks.named<Test>("test") {
    useJUnitPlatform()
    // The library promises it needs no native access: heap and caller-supplied
    // segments only, no downcalls. Deny it outright so a restricted call fails
    // the suite rather than printing a warning nobody reads.
    jvmArgs("--illegal-native-access=deny")
    // msgpack-core is the oracle here and reaches for sun.misc.Unsafe; that is
    // the very warning this library exists to remove, so keep it out of the log.
    jvmArgs("--sun-misc-unsafe-memory-access=allow")
    // Fuzz seeds are printed on failure; -Pmsgpack.seed=<n> replays one.
    (findProperty("msgpack.seed") as String?)?.let { systemProperty("msgpack.seed", it) }
    testLogging {
        events("failed", "skipped")
        exceptionFormat = TestExceptionFormat.FULL
    }
}

tasks.register<JavaExec>("bench") {
    description = "Rough encode/decode throughput against msgpack-core (not a gate)"
    group = "verification"
    classpath = sourceSets["test"].runtimeClasspath
    mainClass = "com.botwithus.msgpack.CodecBench"
    jvmArgs("--illegal-native-access=deny", "--sun-misc-unsafe-memory-access=allow")
}

tasks.named<Javadoc>("javadoc") {
    // `-missing` because a one-line `/** @return ... */` is the house style; every
    // other doclint group is on, and any warning fails the build.
    val doclet = options as StandardJavadocDocletOptions
    doclet.addStringOption("Xdoclint:all,-missing", "-quiet")
    doclet.addBooleanOption("Xwerror", true)
}

publishing {
    publications {
        create<MavenPublication>("mavenJava") {
            from(components["java"])
            artifactId = "msgpack"

            pom {
                name = "panama-msgpack"
                description = "MessagePack for Java on java.lang.foreign (Panama): " +
                    "no sun.misc.Unsafe, no dependencies, bounds-checked decoding."
                url = "https://github.com/BotWithUs/panama-msgpack"

                licenses {
                    license {
                        name = "GNU General Public License, version 3"
                        url = "https://www.gnu.org/licenses/gpl-3.0.txt"
                    }
                }

                developers {
                    developer {
                        id = "botwithus"
                        name = "BotWithUs"
                        url = "https://github.com/BotWithUs"
                    }
                }

                scm {
                    connection = "scm:git:https://github.com/BotWithUs/panama-msgpack.git"
                    developerConnection = "scm:git:ssh://git@github.com/BotWithUs/panama-msgpack.git"
                    url = "https://github.com/BotWithUs/panama-msgpack"
                }
            }
        }
    }

    repositories {
        maven {
            name = "staticTree"
            // A static Maven repo is just a file tree served over HTTPS. The
            // publish workflow checks out BotWithUs/maven and points
            // `-PmavenRepoDir` at that working copy, so Gradle merges
            // `maven-metadata.xml` against the versions already published
            // rather than replacing the version list with just this one.
            // Left unset, local builds stage into build/ for inspection.
            url = uri(
                providers.gradleProperty("mavenRepoDir")
                    .getOrElse(layout.buildDirectory.dir("staging-repo").get().asFile.path)
            )
        }
    }
}
