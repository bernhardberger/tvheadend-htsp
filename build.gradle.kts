import org.gradle.api.artifacts.component.ModuleComponentIdentifier
import org.gradle.api.publish.maven.MavenPublication
import org.gradle.api.publish.maven.tasks.PublishToMavenLocal
import org.gradle.api.tasks.bundling.Jar
import org.gradle.api.tasks.compile.JavaCompile
import org.gradle.api.tasks.testing.Test
import dev.detekt.gradle.Detekt
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinJvmCompile
import java.security.MessageDigest

plugins {
    `java-library`
    `maven-publish`
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.detekt)
    alias(libs.plugins.dokka)
}

group = "at.bernhardberger.tvheadend"
version = "0.1.1"

val releaseVersion = version.toString().removeSuffix("-SNAPSHOT")
val allowedPublicationVersions = setOf(
    "$releaseVersion-SNAPSHOT",
    releaseVersion,
)
check(
    Regex("0\\.(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)").matches(releaseVersion) &&
        version.toString() in allowedPublicationVersions,
) {
    "Publication version must be a strict major-zero release or snapshot: $version"
}

kotlin {
    jvmToolchain(21)
    explicitApi()
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
        freeCompilerArgs.add("-Xjdk-release=17")
    }
    @OptIn(org.jetbrains.kotlin.gradle.dsl.abi.ExperimentalAbiValidation::class)
    abiValidation()
}

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(21))
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
    withSourcesJar()
    withJavadocJar()
}

dependencies {
    api(libs.kotlinx.coroutines.core)
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.konsist)
    testRuntimeOnly(libs.junit.platform.launcher)
}

detekt {
    config.setFrom("detekt.yml")
    source.setFrom("src/main/kotlin")
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

tasks.withType<Jar>()
    .matching { task ->
        task.name == "jar" ||
            task.name == "sourcesJar" ||
            task.name == "javadocJar"
    }
    .configureEach {
        from("LICENSE") {
            into("META-INF")
        }
        from("NOTICE.md") {
            into("META-INF")
        }
    }

configurations.named("sourcesElements") {
    val sourceArtifacts = outgoing.artifacts
    val sourcesJarArtifacts = sourceArtifacts.filter { artifact ->
        artifact.buildDependencies.getDependencies(null).any { task ->
            task.name == "sourcesJar"
        }
    }
    check(sourceArtifacts.size == 1 && sourcesJarArtifacts.size == 1) {
        "The publication must expose exactly one shared sourcesJar artifact, found $sourceArtifacts"
    }
}

tasks.named<Jar>("javadocJar") {
    from(tasks.dokkaGeneratePublicationHtml.flatMap { it.outputDirectory })
    from("README.md") {
        into("docs")
        rename { "ROOT_README.md" }
    }
    from("docs") {
        include("**/*.md")
        into("docs")
    }
}

val checkoutLocalMavenRepository = layout.buildDirectory.dir("local-maven")

publishing {
    publications {
        create<MavenPublication>("htsp") {
            from(components["java"])
            artifactId = "htsp"
            pom {
                name.set("HTSP for Kotlin/JVM")
                description.set("A standalone Kotlin/JVM client library for the TVHeadend HTSP protocol.")
                url.set("https://github.com/bernhardberger/tvheadend-htsp")
                licenses {
                    license {
                        name.set("GNU General Public License v3.0")
                        url.set("https://www.gnu.org/licenses/gpl-3.0.html")
                        distribution.set("repo")
                    }
                }
                developers {
                    developer {
                        id.set("bernhardberger")
                        name.set("Bernhard Berger")
                        url.set("https://github.com/bernhardberger")
                    }
                }
                scm {
                    connection.set("scm:git:https://github.com/bernhardberger/tvheadend-htsp.git")
                    developerConnection.set("scm:git:ssh://git@github.com/bernhardberger/tvheadend-htsp.git")
                    url.set("https://github.com/bernhardberger/tvheadend-htsp")
                    tag.set("HEAD")
                }
                issueManagement {
                    system.set("GitHub Issues")
                    url.set("https://github.com/bernhardberger/tvheadend-htsp/issues")
                }
                properties.put("tvheadend.htsp.predecessor.name", "TVHStream")
                properties.put("tvheadend.htsp.predecessor.url", "https://github.com/Preclikos/tvhstream")
                properties.put("tvheadend.htsp.notice.path", "NOTICE.md")
            }
        }
    }
    repositories {
        maven {
            name = "checkoutLocal"
            url = checkoutLocalMavenRepository.get().asFile.toURI()
        }
    }
}

tasks.withType<PublishToMavenLocal>().configureEach {
    enabled = false
}

val publicationVersion = version.toString()
val publicationVersionDirectory = checkoutLocalMavenRepository.map { repository ->
    repository.dir("at/bernhardberger/tvheadend/htsp/$publicationVersion")
}

val writePublicationChecksums = tasks.register("writePublicationChecksums") {
    group = "publishing"
    description = "Writes SHA-256 sidecars for the five staged HTSP publication artifacts."
    dependsOn("publishHtspPublicationToCheckoutLocalRepository")
    inputs.property("publicationVersion", publicationVersion)
    inputs.property("allowedPublicationVersions", allowedPublicationVersions.toList())
    inputs.property(
        "publicationVersionDirectory",
        publicationVersionDirectory.map { directory -> directory.asFile.absolutePath },
    )
    doLast {
        val publicationVersion = inputs.properties.getValue("publicationVersion") as String
        val allowedPublicationVersions =
            (inputs.properties.getValue("allowedPublicationVersions") as List<*>).filterIsInstance<String>()
        check(publicationVersion in allowedPublicationVersions) {
            "Unsupported publication version: $publicationVersion"
        }
        val versionDirectory = File(inputs.properties.getValue("publicationVersionDirectory") as String)
        check(versionDirectory.isDirectory) {
            "Staged publication directory is missing: $versionDirectory"
        }
        val artifacts = if (publicationVersion.endsWith("-SNAPSHOT")) {
            val releaseVersion = Regex.escape(publicationVersion.removeSuffix("-SNAPSHOT"))
            val artifactPattern = Regex(
                "^htsp-$releaseVersion-\\d{8}\\.\\d{6}-\\d+" +
                    "(?:-sources|-javadoc)?\\.(?:jar|pom|module)$",
            )
            versionDirectory.listFiles()
                ?.filter { file -> file.isFile && artifactPattern.matches(file.name) }
                ?.sortedBy { file -> file.name }
                .orEmpty()
        } else {
            listOf(
                "htsp-$publicationVersion.jar",
                "htsp-$publicationVersion-sources.jar",
                "htsp-$publicationVersion-javadoc.jar",
                "htsp-$publicationVersion.pom",
                "htsp-$publicationVersion.module",
            ).map { name -> versionDirectory.resolve(name) }
                .filter { file -> file.isFile }
                .sortedBy { file -> file.name }
        }
        check(artifacts.size == 5) {
            "Expected five staged publication artifacts, found ${artifacts.map { it.name }}"
        }
        artifacts.forEach { artifact ->
            val digest = MessageDigest.getInstance("SHA-256")
                .digest(artifact.readBytes())
                .joinToString("") { byte -> "%02x".format(byte) }
            artifact.parentFile.resolve("${artifact.name}.sha256").writeText("$digest\n")
        }
    }
}

tasks.register("stageLocalPublication") {
    group = "publishing"
    description = "Stages the one allowed HTSP publication under build/local-maven."
    dependsOn(writePublicationChecksums)
}

val productionClasses = sourceSets["main"].output.classesDirs

tasks.register("verifyClassMajor61") {
    group = "verification"
    description = "Checks every production class uses Java 17 class-file major version 61."
    dependsOn("classes")
    inputs.files(productionClasses)
    doLast {
        val classes = inputs.files.asFileTree.matching {
            include("**/*.class")
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

val directProductionDependencies = configurations.named("api").map { configuration ->
    configuration.dependencies
        .map { dependency -> "${dependency.group}:${dependency.name}:${dependency.version}" }
        .sorted()
}
val resolvedProductionDependencies = configurations.named("runtimeClasspath").map { configuration ->
    configuration.incoming.resolutionResult.allComponents
        .mapNotNull { component -> component.id as? ModuleComponentIdentifier }
        .map { identifier -> "${identifier.group}:${identifier.module}:${identifier.version}" }
        .sorted()
}

tasks.register("verifyProductionDependencyGraph") {
    group = "verification"
    description = "Checks the JVM-only production dependency graph."
    inputs.property("directDependencies", directProductionDependencies)
    inputs.property("resolvedDependencies", resolvedProductionDependencies)
    doLast {
        val direct = (inputs.properties.getValue("directDependencies") as List<*>)
            .filterIsInstance<String>()
            .toSortedSet()
        val expectedDirect = sortedSetOf(
            "org.jetbrains.kotlin:kotlin-stdlib:2.4.10",
            "org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2",
        )
        check(direct == expectedDirect) {
            "Unexpected direct production dependencies: $direct"
        }
        val resolved = (inputs.properties.getValue("resolvedDependencies") as List<*>)
            .filterIsInstance<String>()
            .toSet()
        val allowed = setOf(
            "org.jetbrains:annotations:23.0.0",
            "org.jetbrains.kotlin:kotlin-stdlib:2.4.10",
            "org.jetbrains.kotlinx:kotlinx-coroutines-bom:1.10.2",
            "org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2",
            "org.jetbrains.kotlinx:kotlinx-coroutines-core-jvm:1.10.2",
        )
        check(resolved == allowed) { "Unexpected production resolution graph: $resolved" }
    }
}

tasks.named("check") {
    dependsOn("checkKotlinAbi", "verifyClassMajor61", "verifyProductionDependencyGraph")
}
