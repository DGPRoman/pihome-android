pluginManagement {
    repositories {
        // Google's repository is consulted only for the groups it publishes, so a
        // name it happened to hold could not stand in for one from Maven Central.
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    // Every module resolves from this one list; a module that declared its own
    // repositories would be a second, unreviewed place a dependency could come from.
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
    }
}

rootProject.name = "pihome-android"

include(":app", ":hub-client")
