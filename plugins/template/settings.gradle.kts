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
        mavenLocal {
            content {
                includeGroup("io.github.sadami3066.superpi")
            }
        }
        maven {
            url = uri("https://sadami3066.github.io/SuperPI-Maven")
            content {
                includeGroup("io.github.sadami3066.superpi")
            }
        }
        google()
        mavenCentral()
    }
}

rootProject.name = "plugin-template"
