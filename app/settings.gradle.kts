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
        exclusiveContent {
            forRepository {
                maven {
                    name = "ContakoSourceBuiltCrypto"
                    url = uri("native/build/maven")
                }
            }
            filter { includeGroup("com.patmanak.contako.crypto") }
        }
        google()
        mavenCentral()
    }
}

rootProject.name = "Contako"
include(":hostile-test-app")
