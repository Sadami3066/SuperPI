import com.android.build.api.variant.HasUnitTest

plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "io.github.whywood.superpi.runtime"
    compileSdk = 36

    defaultConfig {
        minSdk = 29
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
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

dependencies {
    api(project(":runtime-api"))
    api(libs.pi.coding.agent.android)
    implementation(libs.androidx.sqlite.bundled)
    implementation(libs.okio)
    implementation(libs.androidx.core)
    implementation(libs.datastore.preferences)
    implementation(libs.coroutines.android)
    testImplementation(libs.junit)
    testImplementation(libs.coroutines.test)
}
