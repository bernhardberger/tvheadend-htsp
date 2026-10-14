pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        val expectedRepository = rootDir.parentFile.resolve("build/local-maven")
            .toPath().toAbsolutePath().normalize()
        val checkoutLocalRepository = file("../build/local-maven")
            .toPath().toAbsolutePath().normalize()
        check(checkoutLocalRepository == expectedRepository) {
            "HTSP repository must be the sibling checkout's build/local-maven directory"
        }
        check(checkoutLocalRepository.toFile().isDirectory) {
            "Staged HTSP repository is missing: $checkoutLocalRepository"
        }
        check(checkoutLocalRepository.toRealPath() == expectedRepository) {
            "Staged HTSP repository must not traverse a symlink or escape the checkout"
        }
        maven {
            name = "htspCheckoutLocal"
            url = checkoutLocalRepository.toUri()
            content {
                includeGroup("at.bernhardberger.tvheadend")
                notForConfigurations("baselineCompileClasspath")
            }
        }
        // Only the old consumer's compilation may use the released library.
        // Every other HTSP resolution, especially test runtime, stays checkout-local.
        mavenCentral {
            name = "htspCompatibilityBaseline"
            content {
                includeModule("at.bernhardberger.tvheadend", "htsp")
                onlyForConfigurations("baselineCompileClasspath")
            }
        }
        mavenCentral {
            content {
                excludeGroup("at.bernhardberger.tvheadend")
            }
        }
    }
}

rootProject.name = "htsp-consumer-contract"
