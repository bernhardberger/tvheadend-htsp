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

dependencies {
    implementation(libs.htsp) {
        version { strictly(htspVersion) }
    }
    testImplementation(libs.junit)
    testRuntimeOnly(libs.junit.platform.launcher)
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
