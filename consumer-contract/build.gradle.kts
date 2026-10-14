import java.security.MessageDigest
import java.util.HexFormat
import org.gradle.api.artifacts.component.ModuleComponentIdentifier
import org.gradle.api.tasks.compile.JavaCompile
import org.gradle.api.tasks.testing.Test
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinJvmCompile

plugins {
    alias(libs.plugins.kotlin.jvm)
}

kotlin {
    jvmToolchain(21)
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
        freeCompilerArgs.add("-Xjdk-release=17")
    }
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

val htspVersion = Regex("""(?m)^version = "([^"]+)"[ \t]*$""")
    .findAll(file("../build.gradle.kts").readText())
    .singleOrNull()
    ?.groupValues
    ?.get(1)
    ?: error("The root project must declare exactly one literal version")

// Compile these call sites against the immutable release, then execute their
// unchanged bytecode against the staged candidate on the ordinary test classpath.
val baseline = sourceSets.create("baseline")

dependencies {
    implementation(libs.htsp) {
        version { strictly(htspVersion) }
    }
    testImplementation(libs.junit)
    testRuntimeOnly(libs.junit.platform.launcher)
    add(baseline.compileOnlyConfigurationName, "at.bernhardberger.tvheadend:htsp:1.0.0-beta.1")
    testImplementation(baseline.output)
}

val baselineClasspath = configurations.named(baseline.compileClasspathConfigurationName)
val verifyBaselineArtifact = tasks.register("verifyBaselineArtifact") {
    group = "verification"
    description = "Verifies that legacy call sites compile against the immutable beta.1 release."
    inputs.files(baselineClasspath)
    doLast {
        val baselineJar = inputs.files.single { it.name == "htsp-1.0.0-beta.1.jar" }
        val digest = HexFormat.of().formatHex(
            MessageDigest.getInstance("SHA-256").digest(baselineJar.readBytes()),
        )
        // Published SHA-256 recorded in docs/versioning.md, not a locally staged build.
        check(digest == "824e24dde59891ad2670df6ca927382e4764a10073491a72f5f1ef9fb2addc68") {
            "The compatibility baseline is not the published 1.0.0-beta.1 JAR"
        }
    }
}

tasks.named(baseline.getCompileTaskName("kotlin")) {
    dependsOn(verifyBaselineArtifact)
}

tasks.withType<JavaCompile>().configureEach {
    options.release.set(17)
}

tasks.withType<KotlinJvmCompile>().configureEach {
    compilerOptions.jvmTarget.set(JvmTarget.JVM_17)
    compilerOptions.freeCompilerArgs.add("-Xjdk-release=17")
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
}

tasks.register("verifyConsumerDependencyGraph") {
    group = "verification"
    description = "Runs the staged runtime contract and verifies the resolved HTSP JAR bytes."
    dependsOn("test")

    doLast {
        val htspGroup = "at.bernhardberger.tvheadend"
        val htspModule = "htsp"
        val resolvedHtspJar = configurations.getByName("testRuntimeClasspath")
            .incoming.artifacts.artifacts.single { artifact ->
                val identifier = artifact.id.componentIdentifier as? ModuleComponentIdentifier
                identifier?.group == htspGroup &&
                    identifier.module == htspModule &&
                    identifier.version == htspVersion
            }
            .file
        val stagedHtspJar = rootDir.resolve(
            "../build/local-maven/at/bernhardberger/tvheadend/htsp/" +
                "$htspVersion/htsp-$htspVersion.jar",
        )
        check(stagedHtspJar.isFile) { "The staged HTSP JAR is missing: $stagedHtspJar" }
        val stagedDigest = HexFormat.of().formatHex(
            MessageDigest.getInstance("SHA-256").digest(stagedHtspJar.readBytes()),
        )
        val expectedDigest = stagedHtspJar.parentFile
            .resolve("${stagedHtspJar.name}.sha256")
            .readText()
            .trim()
        check(stagedDigest == expectedDigest) { "The staged HTSP JAR checksum differs from its sidecar" }
        check(resolvedHtspJar.readBytes().contentEquals(stagedHtspJar.readBytes())) {
            "The resolved HTSP JAR bytes differ from the staged JAR: $resolvedHtspJar"
        }
    }
}
