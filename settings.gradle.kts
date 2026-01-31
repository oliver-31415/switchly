pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }

    plugins {
        // Android Gradle Plugin
        id("com.android.application") version "8.9.1"
        id("com.android.library") version "8.9.1"

        // Kotlin
        id("org.jetbrains.kotlin.android") version "2.2.21"
        id("org.jetbrains.kotlin.kapt") version "2.2.21"

        // KSP
        id("com.google.devtools.ksp") version "2.2.21-2.0.4"

        // Google Services
        id("com.google.gms.google-services") version "4.4.4"

        // Firebase Crashlytics
        id("com.google.firebase.crashlytics") version "3.0.6"
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "switchly"
include(":app")
