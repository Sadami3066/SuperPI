pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        mavenLocal {
            content { includeGroup("io.github.whywood.superpi.sdk") }
        }
        maven("https://sadami3066.github.io/SuperPI-Maven") {
            content { includeGroup("io.github.whywood.superpi.sdk") }
        }
        google()
        mavenCentral()
        maven("https://jitpack.io")
    }
}

rootProject.name = "superpi"

val hostOnly = providers.gradleProperty("superpi.hostOnly").orNull == "true" ||
    providers.gradleProperty("superpi.pcOnly").orNull == "true" ||
    System.getenv("SUPERPI_HOST_ONLY") == "1" ||
    System.getenv("SUPERPI_PC_ONLY") == "1"

val hasAndroidSdk = !hostOnly && (
    !System.getenv("ANDROID_HOME").isNullOrBlank() ||
        !System.getenv("ANDROID_SDK_ROOT").isNullOrBlank() ||
        file("local.properties").isFile ||
        file("${System.getProperty("user.home")}/Android/Sdk").isDirectory ||
        file("${System.getProperty("user.home")}/Library/Android/sdk").isDirectory ||
        file("/usr/local/lib/android/sdk").isDirectory ||
        file("/opt/android-sdk").isDirectory
)

include(
    ":core:runtime-api",
    ":core:runtime",
    ":core:relay",
    ":apps:host",
)

if (hasAndroidSdk) {
    include(
        ":apps:android:app",
        ":apps:android:shell",
        ":apps:android:design",
        ":apps:android:plugin-api",
    )
}

val useLocalSdk = providers.gradleProperty("superpi.localSdk").orNull == "true" ||
    System.getenv("SUPERPI_LOCAL_SDK") == "1"
if (useLocalSdk) {
    val sdkDir = file("../SuperPI-KotlinSDK")
    if (sdkDir.isDirectory) {
        includeBuild(sdkDir)
    }
}
