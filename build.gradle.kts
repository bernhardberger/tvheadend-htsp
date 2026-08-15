import org.gradle.api.artifacts.component.ModuleComponentIdentifier
import org.gradle.api.tasks.compile.JavaCompile
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinJvmCompile

plugins {
    `java-library`
    kotlin("jvm") version "2.3.10"
}

group = "at.bernhardberger.tvheadend"
version = "0.1.0-alpha.1-SNAPSHOT"

kotlin {
    jvmToolchain(21)
    explicitApi()
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
        freeCompilerArgs.add("-Xjdk-release=17")
    }
    @OptIn(org.jetbrains.kotlin.gradle.dsl.abi.ExperimentalAbiValidation::class)
    abiValidation {
        enabled.set(true)
    }
}

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(21))
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

dependencies {
    api("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
}

tasks.withType<JavaCompile>().configureEach {
    options.release.set(17)
}

tasks.withType<KotlinJvmCompile>().configureEach {
    compilerOptions.jvmTarget.set(JvmTarget.JVM_17)
    compilerOptions.freeCompilerArgs.add("-Xjdk-release=17")
}

tasks.register("verifyClassMajor61") {
    group = "verification"
    description = "Checks every production class uses Java 17 class-file major version 61."
    dependsOn("classes")
    doLast {
        val classes = fileTree(layout.buildDirectory.dir("classes")) {
            include("**/main/**/*.class")
        }.files.sortedBy { file -> file.invariantSeparatorsPath }
        check(classes.isNotEmpty()) { "No production class files were found" }
        classes.forEach { file ->
            val header = file.inputStream().use { input -> input.readNBytes(8) }
            check(header.size == 8 && header[0] == 0xCA.toByte() && header[1] == 0xFE.toByte()) {
                "Malformed class file: $file"
            }
            val major = (header[6].toInt() and 0xff) * 256 + (header[7].toInt() and 0xff)
            check(major == 61) { "$file uses class-file major $major, expected 61" }
        }
    }
}

tasks.register("verifyProductionDependencyGraph") {
    group = "verification"
    description = "Checks the JVM-only production dependency graph."
    doLast {
        val direct = configurations.getByName("api").dependencies
            .map { dependency -> "${dependency.group}:${dependency.name}:${dependency.version}" }
            .toSortedSet()
        val expectedDirect = sortedSetOf(
            "org.jetbrains.kotlin:kotlin-stdlib:2.3.10",
            "org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2",
        )
        check(direct == expectedDirect) {
            "Unexpected direct production dependencies: $direct"
        }
        val resolved = configurations.getByName("runtimeClasspath")
            .incoming.resolutionResult.allComponents
            .mapNotNull { component -> component.id as? ModuleComponentIdentifier }
            .map { identifier -> "${identifier.group}:${identifier.module}:${identifier.version}" }
            .toSet()
        val allowed = setOf(
            "org.jetbrains:annotations:23.0.0",
            "org.jetbrains.kotlin:kotlin-stdlib:2.3.10",
            "org.jetbrains.kotlinx:kotlinx-coroutines-bom:1.10.2",
            "org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2",
            "org.jetbrains.kotlinx:kotlinx-coroutines-core-jvm:1.10.2",
        )
        check(resolved == allowed) { "Unexpected production resolution graph: $resolved" }
    }
}
