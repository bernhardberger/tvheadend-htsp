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
    implementation("at.bernhardberger.tvheadend:htsp:0.1.0-alpha.1-SNAPSHOT")
}

tasks.withType<JavaCompile>().configureEach {
    options.release.set(17)
}

tasks.withType<KotlinJvmCompile>().configureEach {
    compilerOptions.jvmTarget.set(JvmTarget.JVM_17)
    compilerOptions.freeCompilerArgs.add("-Xjdk-release=17")
}

tasks.register("verifyConsumerDependencyGraph") {
    group = "verification"
    description = "Checks the isolated consumer's complete JVM dependency graph."
    dependsOn("classes")

    doLast {
        val htspGroup = "at.bernhardberger.tvheadend"
        val htspModule = "htsp"
        val htspVersion = "0.1.0-alpha.1-SNAPSHOT"

        check(gradle.includedBuilds.isEmpty()) {
            "The HTSP consumer must not use included builds or composite substitution"
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
            "The HTSP consumer has local dependency declarations: $localDependencyDeclarations"
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
            "implementation:org.jetbrains.kotlin:kotlin-stdlib:2.3.10",
            "implementation:$htspGroup:$htspModule:$htspVersion",
        )
        check(productionDependencyDeclarations == expectedProductionDependencies) {
            "The HTSP consumer production dependencies are " +
                "$productionDependencyDeclarations, expected $expectedProductionDependencies"
        }

        val runtimeClasspath = configurations.getByName("runtimeClasspath")
        val runtimeComponents = runtimeClasspath.incoming.resolutionResult.allComponents
            .mapNotNull { component -> component.id as? ModuleComponentIdentifier }
        val resolvedExternalComponents = runtimeComponents.map { identifier ->
            "${identifier.group}:${identifier.module}:${identifier.version}"
        }.sorted()
        val expectedExternalComponents = listOf(
            "at.bernhardberger.tvheadend:htsp:0.1.0-alpha.1-SNAPSHOT",
            "org.jetbrains:annotations:23.0.0",
            "org.jetbrains.kotlin:kotlin-stdlib:2.3.10",
            "org.jetbrains.kotlinx:kotlinx-coroutines-bom:1.10.2",
            "org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2",
            "org.jetbrains.kotlinx:kotlinx-coroutines-core-jvm:1.10.2",
        ).sorted()
        check(resolvedExternalComponents == expectedExternalComponents) {
            "The HTSP consumer runtime components are $resolvedExternalComponents, " +
                "expected $expectedExternalComponents"
        }
        val forbiddenComponents = runtimeComponents.filter { identifier ->
            identifier.group == "androidx" ||
                identifier.group.startsWith("androidx.") ||
                identifier.module.contains("android", ignoreCase = true) ||
                identifier.module.contains("media3", ignoreCase = true) ||
                identifier.module.contains("decoder", ignoreCase = true) ||
                identifier.module.contains("native", ignoreCase = true)
        }
        check(forbiddenComponents.isEmpty()) {
            "The HTSP consumer resolved Android, Media3, decoder, or native components: " +
                forbiddenComponents.map { identifier -> identifier.displayName }
        }

        val runtimeArtifacts = runtimeClasspath.incoming.artifacts.artifacts
        val runtimeArtifactCoordinates = runtimeArtifacts.map { artifact ->
            val identifier = artifact.id.componentIdentifier as? ModuleComponentIdentifier
                ?: error("The HTSP consumer resolved a local artifact: ${artifact.file}")
            "${identifier.group}:${identifier.module}:${identifier.version}:" +
                artifact.file.extension.lowercase()
        }.sorted()
        val expectedArtifactCoordinates = listOf(
            "$htspGroup:$htspModule:$htspVersion:jar",
            "org.jetbrains:annotations:23.0.0:jar",
            "org.jetbrains.kotlin:kotlin-stdlib:2.3.10:jar",
            "org.jetbrains.kotlinx:kotlinx-coroutines-core-jvm:1.10.2:jar",
        ).sorted()
        check(runtimeArtifactCoordinates == expectedArtifactCoordinates) {
            "The HTSP consumer runtime artifacts are $runtimeArtifactCoordinates, " +
                "expected $expectedArtifactCoordinates"
        }
        val forbiddenArtifacts = runtimeArtifacts.filter { artifact ->
            val extension = artifact.file.extension.lowercase()
            val name = artifact.file.name.lowercase()
            extension in setOf("aar", "so", "dll", "dylib") ||
                name.contains("android") || name.contains("media3") ||
                name.contains("decoder") || name.contains("native")
        }
        check(forbiddenArtifacts.isEmpty()) {
            "The HTSP consumer resolved Android, Media3, decoder, or native artifacts: " +
                forbiddenArtifacts.map { artifact -> artifact.file }
        }

        val localRepository = layout.projectDirectory
            .dir("../build/local-maven")
            .asFile
            .canonicalFile
        check(localRepository == rootDir.resolve("../build/local-maven").canonicalFile) {
            "The HTSP consumer repository escaped the sibling checkout build directory"
        }
        val expectedVersionDirectory = localRepository
            .resolve("at/bernhardberger/tvheadend/htsp")
            .resolve(htspVersion)
            .absoluteFile
        val versionDirectory = expectedVersionDirectory.canonicalFile
        check(versionDirectory == expectedVersionDirectory) {
            "The HTSP snapshot directory escaped the canonical staged module path"
        }
        val metadata = versionDirectory.resolve("maven-metadata.xml")
        check(metadata.isFile && !java.nio.file.Files.isSymbolicLink(metadata.toPath())) {
            "HTSP snapshot metadata is missing from $versionDirectory"
        }
        val snapshotStem = Regex(
            "^htsp-0\\.1\\.0-alpha\\.1-\\d{8}\\.\\d{6}-[1-9]\\d*",
        )
        val primaryArtifacts = versionDirectory.listFiles()
            .orEmpty()
            .filter { file ->
                file.isFile && snapshotStem.containsMatchIn(file.name) &&
                    file.extension in setOf("jar", "pom", "module") &&
                    !file.name.endsWith(".sha256")
            }
        val primarySuffixes = primaryArtifacts.map { file ->
            file.name.replace(snapshotStem, "")
        }.sorted()
        val expectedPrimarySuffixes = listOf(
            "-javadoc.jar",
            "-sources.jar",
            ".jar",
            ".module",
            ".pom",
        )
        check(primarySuffixes == expectedPrimarySuffixes) {
            "The staged HTSP snapshot artifacts are $primarySuffixes, " +
                "expected $expectedPrimarySuffixes"
        }
        val stagedMainJar = primaryArtifacts.single { file ->
            file.name.replace(snapshotStem, "") == ".jar"
        }
        check(
            java.nio.file.Files.isRegularFile(
                stagedMainJar.toPath(),
                java.nio.file.LinkOption.NOFOLLOW_LINKS,
            ),
        ) {
            "The staged HTSP main JAR is not a regular non-symlink file: $stagedMainJar"
        }
        primaryArtifacts.forEach { artifact ->
            check(versionDirectory.resolve("${artifact.name}.sha256").isFile) {
                "The staged HTSP artifact has no SHA-256 sidecar: ${artifact.name}"
            }
        }
        val allowedFiles = buildSet {
            add(metadata.name)
            primaryArtifacts.forEach { artifact -> add(artifact.name) }
            (setOf(metadata.name) + primaryArtifacts.map { artifact -> artifact.name })
                .forEach { name ->
                    setOf("md5", "sha1", "sha256", "sha512").forEach { algorithm ->
                        add("$name.$algorithm")
                    }
                }
        }
        val unexpectedFiles = versionDirectory.listFiles()
            .orEmpty()
            .filter { file -> !file.isFile || file.name !in allowedFiles }
        check(unexpectedFiles.isEmpty()) {
            "The staged HTSP snapshot contains unexpected inventory: $unexpectedFiles"
        }

        val htspArtifacts = runtimeArtifacts.filter { artifact ->
            val identifier = artifact.id.componentIdentifier as? ModuleComponentIdentifier
            identifier?.group == htspGroup && identifier.module == htspModule
        }
        check(htspArtifacts.size == 1) {
            "The HTSP consumer resolved ${htspArtifacts.size} HTSP artifacts: $htspArtifacts"
        }
        val htspArtifact = htspArtifacts.single().file
        check(
            java.nio.file.Files.isRegularFile(
                htspArtifact.toPath(),
                java.nio.file.LinkOption.NOFOLLOW_LINKS,
            ),
        ) {
            "The resolved HTSP artifact is not a regular non-symlink file: $htspArtifact"
        }
        check(htspArtifact.readBytes().contentEquals(stagedMainJar.readBytes())) {
            "The resolved HTSP artifact bytes differ from the staged snapshot JAR: $htspArtifact"
        }
    }
}
