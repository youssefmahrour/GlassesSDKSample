pluginManagement {
    repositories {
        maven {
            url = uri("https://jitpack.io")
        }
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
        flatDir {
            dirs("libs")
        }
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        maven {
            url = uri("https://jitpack.io")
        }
        google()
        mavenCentral()
    }
}

rootProject.name = "GlassesSDKSample"
include(":app")
// Remove the non-existent LIB_GLASSES_SDK module
// include(":LIB_GLASSES_SDK")