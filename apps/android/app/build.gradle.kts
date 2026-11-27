import com.android.build.api.variant.HasUnitTest
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

val secretsDir = File(
    System.getenv("SUPERPI_SECRETS_DIR") ?: "${System.getProperty("user.home")}/.config/superpi/secrets",
)
val keystorePropertiesFile = listOfNotNull(
    rootProject.file("apps/android/keystore.properties").takeIf { it.isFile },
    rootProject.file("keystore.properties").takeIf { it.isFile },
    System.getenv("SUPERPI_KEYSTORE_PROPERTIES")?.let(::File)?.takeIf { it.isFile },
    File(secretsDir, "keystore.properties").takeIf { it.isFile },
).firstOrNull()
val keystoreProperties = Properties().apply {
    keystorePropertiesFile?.inputStream()?.use { load(it) }
}

android {
    namespace = "io.github.whywood.superpi"
    compileSdk = 36

    defaultConfig {
        applicationId = "io.github.whywood.superpi"
        minSdk = 29
        targetSdk = 36
        versionCode = 20003
        versionName = "2.0.3"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    androidResources {
        noCompress += "rootfs"
    }

    packaging {
        jniLibs.useLegacyPackaging = true
    }

    signingConfigs {
        create("release") {
            if (keystorePropertiesFile != null) {
                val rawStoreFile = keystoreProperties.getProperty("storeFile", "release.keystore")
                storeFile = File(rawStoreFile).takeIf { it.isAbsolute }
                    ?: File(keystorePropertiesFile.parentFile, rawStoreFile).takeIf { it.isFile }
                    ?: file(rawStoreFile)
                storePassword = keystoreProperties.getProperty("storePassword")
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            signingConfig = if (keystorePropertiesFile != null) {
                signingConfigs.getByName("release")
            } else {
                signingConfigs.getByName("debug")
            }
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }
}

androidComponents {
    onVariants { variant ->
        (variant as? HasUnitTest)?.unitTest?.let { unitTest ->
            unitTest.runtimeConfiguration.resolutionStrategy.dependencySubstitution {
                substitute(module("androidx.sqlite:sqlite-bundled"))
                    .using(module("androidx.sqlite:sqlite-bundled-jvm:${libs.versions.sqlite.get()}"))
            }
        }
    }
}

kotlin {
    jvmToolchain(17)
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

composeCompiler {
    stabilityConfigurationFiles.add(layout.projectDirectory.file("compose-stability.conf"))
    reportsDestination.set(layout.buildDirectory.dir("compose-reports"))
}

dependencies {
    implementation(project(":core:runtime"))
    implementation(project(":core:relay"))
    implementation(project(":apps:android:shell"))
    implementation(project(":apps:android:design"))
    implementation(project(":apps:android:plugin-api"))

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.foundation)
    implementation(libs.compose.animation)
    implementation(libs.compose.material3)
    implementation(libs.compose.preview)
    implementation(libs.activity.compose)
    implementation(libs.androidx.core)
    implementation(libs.lifecycle.compose)
    implementation(libs.lifecycle.viewmodel.compose)
    implementation(libs.lifecycle.viewmodel.ktx)
    implementation(libs.camera.camera2)
    implementation(libs.camera.lifecycle)
    implementation(libs.camera.compose)
    implementation(libs.mlkit.barcode.scanning)
    implementation(libs.datastore.preferences)
    implementation(libs.work.runtime.ktx)
    implementation(libs.coroutines.android)
    implementation(libs.webkit)
    implementation(libs.libadb)
    implementation(libs.hiddenapibypass)
    implementation(libs.bcpkix)
    implementation(libs.okhttp)

    debugImplementation(libs.compose.tooling)
    testImplementation(libs.junit)
    testImplementation(libs.json.org)
    testImplementation(libs.coroutines.test)

    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation(libs.compose.test)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.junit)
    debugImplementation(libs.compose.test.manifest)
}
