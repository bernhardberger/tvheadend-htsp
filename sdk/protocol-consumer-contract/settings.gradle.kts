import java.nio.file.Files

pluginManagement {
    repositories {
        mavenCentral()
        gradlePluginPortal()
    }
}

val checkoutRootPath = file("../..").absoluteFile.normalize().toPath()
check(checkoutRootPath.toFile().canonicalFile.toPath() == checkoutRootPath) {
    "SDK consumer checkout path must not contain symlinks"
}
val localSdkRepositoryPath = checkoutRootPath.resolve("build/local-maven")
listOf(checkoutRootPath.resolve("build"), localSdkRepositoryPath).forEach { path ->
    check(!Files.isSymbolicLink(path)) {
        "SDK consumer repository path must not contain symlinks: $path"
    }
}
val checkoutRoot = checkoutRootPath.toFile()
val localSdkRepository = file("../../build/local-maven").canonicalFile
check(localSdkRepository == checkoutRoot.resolve("build/local-maven").canonicalFile) {
    "SDK consumer repository must remain under the checkout build tree"
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        exclusiveContent {
            forRepository {
                maven {
                    name = "checkoutLocalSdk"
                    url = localSdkRepository.toURI()
                    metadataSources {
                        gradleMetadata()
                        mavenPom()
                    }
                }
            }
            filter {
                includeGroup("at.bernhardberger.tvheadend")
            }
        }
        mavenCentral {
            content {
                excludeGroup("at.bernhardberger.tvheadend")
            }
        }
    }
}

rootProject.name = "protocol-published-consumer-contract"
