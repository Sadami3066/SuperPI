plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.serialization)
}

val includeAndroid = findProject(":apps:android:app") != null
if (includeAndroid) {
    apply(plugin = "com.android.kotlin.multiplatform.library")
}

kotlin {
    if (includeAndroid) {
        extensions.configure<com.android.build.api.dsl.KotlinMultiplatformAndroidLibraryExtension>("android") {
            namespace = "io.github.whywood.superpi.runtime"
            compileSdk = 36
            minSdk = 29
            withHostTest {}
        }
    }

    jvm()
    jvmToolchain(17)

    sourceSets {
        val jvmCommonMain = create("jvmCommonMain") {
            dependsOn(commonMain.get())
            dependencies {
                api(project(":core:runtime-api"))
                api(libs.pi.coding.agent)
                implementation(libs.androidx.sqlite.bundled)
                implementation(libs.okio)
            }
        }

        val jvmCommonTest = create("jvmCommonTest") {
            dependsOn(commonTest.get())
            dependencies {
                implementation(libs.junit)
                implementation(libs.coroutines.test)
            }
        }

        jvmMain.get().dependsOn(jvmCommonMain)
        jvmMain.dependencies {
            implementation(libs.androidx.sqlite.bundled.jvm)
        }

        jvmTest.get().dependsOn(jvmCommonTest)
        jvmTest.dependencies {
            implementation(libs.androidx.sqlite.bundled.jvm)
        }

        if (includeAndroid) {
            androidMain.get().dependsOn(jvmCommonMain)
            androidMain.dependencies {
                implementation(libs.androidx.core)
                implementation(libs.datastore.preferences)
                implementation(libs.coroutines.android)
            }
            findByName("androidHostTest")?.apply {
                dependsOn(jvmCommonTest)
                dependencies {
                    implementation(libs.androidx.sqlite.bundled.jvm)
                }
            }
        }
    }
}
