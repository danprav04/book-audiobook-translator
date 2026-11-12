pluginManagement {
    repositories {
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
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "polyglot-books"

// Application module
include(":app")

// Core modules
include(":core:model")
include(":core:data")
include(":core:gemini")
include(":core:audio")
include(":core:playback")

// Feature modules
include(":feature:library")
include(":feature:audiobook")
include(":feature:translate")
include(":feature:reader")
include(":feature:narration")
include(":feature:settings")
