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
        // Robolectric's 160 MB native runtime is rate-limited per IP on Maven Central; a verified
        // copy in ~/.m2 (if present) is used first. Nothing else is resolved from mavenLocal.
        mavenLocal {
            content { includeModule("org.robolectric", "nativeruntime-dist-compat") }
        }
        google()
        mavenCentral()
    }
}

rootProject.name = "Oralis"
include(":app")
