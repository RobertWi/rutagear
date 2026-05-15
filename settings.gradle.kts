pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // Hammerhead's Karoo extension SDK is published to a GitHub
        // Packages Maven repo. Auth via gpr.user / gpr.key gradle
        // properties — same setup as rutaradar-karoo / rutatail-karoo.
        maven {
            url = uri("https://maven.pkg.github.com/hammerheadnav/karoo-ext")
            credentials {
                username = providers.gradleProperty("gpr.user").orElse("").get()
                password = providers.gradleProperty("gpr.key").orElse("").get()
            }
        }
    }
}

rootProject.name = "RutaGear"
include(":app")
