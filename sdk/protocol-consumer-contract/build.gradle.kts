import org.gradle.api.artifacts.ExternalModuleDependency
import org.gradle.api.artifacts.FileCollectionDependency
import org.gradle.api.artifacts.ProjectDependency
import org.gradle.api.artifacts.component.ModuleComponentIdentifier
import org.gradle.api.tasks.compile.JavaCompile
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinJvmCompile

plugins {
    kotlin("jvm") version "2.3.10"
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

dependencies {
    implementation("at.bernhardberger.tvheadend:htsp-protocol:0.1.0-SNAPSHOT")
}

tasks.withType<JavaCompile>().configureEach {
    options.release.set(17)
}

tasks.withType<KotlinJvmCompile>().configureEach {
    compilerOptions.jvmTarget.set(JvmTarget.JVM_17)
    compilerOptions.freeCompilerArgs.add("-Xjdk-release=17")
}

tasks.register("verifyIndependentProtocolConsumerContract") {
    group = "verification"
    description = "Proves the JVM fixture resolves only the published HTSP protocol coordinate."
    dependsOn("classes")

    doLast {
        val sdkGroup = "at.bernhardberger.tvheadend"
        val sdkModule = "htsp-protocol"
        val sdkVersion = "0.1.0-SNAPSHOT"

        check(gradle.includedBuilds.isEmpty()) {
            "The protocol consumer must not use included builds or composite substitution"
        }

        val localDependencyDeclarations = configurations
            .filter { configuration -> configuration.isCanBeDeclared }
            .flatMap { configuration ->
                configuration.dependencies.mapNotNull { dependency ->
                    when (dependency) {
                        is ProjectDependency ->
                            "${configuration.name}:project:${dependency.path}"
                        is FileCollectionDependency ->
                            "${configuration.name}:files:${dependency.files.files}"
                        else -> null
                    }
                }
            }
        check(localDependencyDeclarations.isEmpty()) {
            "The protocol consumer has local dependency declarations: $localDependencyDeclarations"
        }

        val productionDependencyDeclarations = setOf(
            "api",
            "compileOnly",
            "implementation",
            "runtimeOnly",
        ).mapNotNull { name -> configurations.findByName(name) }
            .flatMap { configuration ->
                configuration.dependencies.map { dependency ->
                    when (dependency) {
                        is ExternalModuleDependency ->
                            "${configuration.name}:${dependency.group}:" +
                                "${dependency.name}:${dependency.version}"
                        is ProjectDependency ->
                            "${configuration.name}:project:${dependency.path}"
                        is FileCollectionDependency ->
                            "${configuration.name}:files:${dependency.files.files}"
                        else -> "${configuration.name}:${dependency.javaClass.name}"
                    }
                }
            }
        val expectedProductionDependencies = listOf(
            "api:org.jetbrains.kotlin:kotlin-stdlib:2.3.10",
            "implementation:$sdkGroup:$sdkModule:$sdkVersion",
        )
        check(productionDependencyDeclarations == expectedProductionDependencies) {
            "The protocol consumer production dependencies are " +
                "$productionDependencyDeclarations, expected $expectedProductionDependencies"
        }

        val runtimeClasspath = configurations.getByName("runtimeClasspath")
        val runtimeComponents = runtimeClasspath.incoming.resolutionResult.allComponents
            .mapNotNull { component -> component.id as? ModuleComponentIdentifier }
        val resolvedExternalComponents = runtimeComponents.map { identifier ->
            "${identifier.group}:${identifier.module}:${identifier.version}"
        }.sorted()
        val expectedExternalComponents = listOf(
            "$sdkGroup:$sdkModule:$sdkVersion",
            "org.jetbrains:annotations:23.0.0",
            "org.jetbrains.kotlin:kotlin-stdlib:2.3.10",
            "org.jetbrains.kotlinx:kotlinx-coroutines-bom:1.10.2",
            "org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2",
            "org.jetbrains.kotlinx:kotlinx-coroutines-core-jvm:1.10.2",
        ).sorted()
        check(resolvedExternalComponents == expectedExternalComponents) {
            "The protocol consumer runtime components are $resolvedExternalComponents, " +
                "expected $expectedExternalComponents"
        }

        val resolvedSdkModules = runtimeComponents
            .filter { identifier -> identifier.group == sdkGroup }
            .map { identifier -> identifier.module to identifier.version }
            .toMap()
        check(resolvedSdkModules == mapOf(sdkModule to sdkVersion)) {
            "The protocol consumer resolved SDK modules $resolvedSdkModules"
        }
        val forbiddenComponents = runtimeComponents.filter { identifier ->
            identifier.group == "androidx" ||
                identifier.group.startsWith("androidx.") ||
                identifier.module.contains("media3", ignoreCase = true) ||
                identifier.module.contains("decoder", ignoreCase = true)
        }
        check(forbiddenComponents.isEmpty()) {
            "The protocol consumer resolved Android, Media3, or decoder components: " +
                forbiddenComponents.map { it.displayName }
        }

        val runtimeArtifacts = runtimeClasspath.incoming.artifacts.artifacts
        val artifactCoordinates = runtimeArtifacts.map { artifact ->
            val identifier = artifact.id.componentIdentifier as? ModuleComponentIdentifier
                ?: error("The protocol consumer resolved a local artifact: ${artifact.file}")
            "${identifier.group}:${identifier.module}:${identifier.version}:" +
                artifact.file.extension.lowercase()
        }.sorted()
        val expectedArtifactCoordinates = listOf(
            "$sdkGroup:$sdkModule:$sdkVersion:jar",
            "org.jetbrains:annotations:23.0.0:jar",
            "org.jetbrains.kotlin:kotlin-stdlib:2.3.10:jar",
            "org.jetbrains.kotlinx:kotlinx-coroutines-core-jvm:1.10.2:jar",
        ).sorted()
        check(artifactCoordinates == expectedArtifactCoordinates) {
            "The protocol consumer runtime artifacts are $artifactCoordinates, " +
                "expected $expectedArtifactCoordinates"
        }
        val forbiddenArtifacts = runtimeArtifacts.filter { artifact ->
            artifact.file.extension.lowercase() in setOf("aar", "so")
        }
        check(forbiddenArtifacts.isEmpty()) {
            "The protocol consumer resolved Android or native artifacts: " +
                forbiddenArtifacts.map { it.file }
        }

        val localRepository = layout.projectDirectory
            .dir("../../build/local-maven")
            .asFile
            .canonicalFile
        check(localRepository == rootDir.resolve("../../build/local-maven").canonicalFile)
        val versionDirectory = localRepository
            .resolve("at/bernhardberger/tvheadend")
            .resolve(sdkModule)
            .resolve(sdkVersion)
        check(versionDirectory.resolve("maven-metadata.xml").isFile) {
            "$sdkModule has no generated snapshot metadata in $versionDirectory"
        }
        listOf("pom", "module", "jar").forEach { requiredExtension ->
            val matches = versionDirectory.listFiles()
                .orEmpty()
                .filter { file ->
                    file.isFile &&
                        file.extension == requiredExtension &&
                        !file.name.endsWith("-sources.jar")
                }
            check(matches.size == 1) {
                "$sdkModule must have one generated .$requiredExtension file, found $matches"
            }
        }
    }
}
