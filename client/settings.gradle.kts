pluginManagement {
    val localMirror = System.getenv("SOREN_LOCAL_MAVEN")
    repositories {
        if (localMirror.isNullOrBlank()) {
            google {
                content {
                    includeGroupByRegex("com\\.android.*")
                    includeGroupByRegex("com\\.google.*")
                    includeGroupByRegex("androidx.*")
                }
            }
            mavenCentral()
            gradlePluginPortal()
        } else {
            maven("$localMirror/google") {
                isAllowInsecureProtocol = true
                content {
                    includeGroupByRegex("com\\.android.*")
                    includeGroupByRegex("com\\.google.*")
                    includeGroupByRegex("androidx.*")
                }
            }
            maven("$localMirror/central") { isAllowInsecureProtocol = true }
            maven("$localMirror/plugins") { isAllowInsecureProtocol = true }
        }
    }
}
dependencyResolutionManagement {
    val localMirror = System.getenv("SOREN_LOCAL_MAVEN")
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        if (localMirror.isNullOrBlank()) {
            google()
            mavenCentral()
            maven { url = uri("https://jitpack.io") }
        } else {
            maven("$localMirror/google") {
                isAllowInsecureProtocol = true
                content {
                    includeGroupByRegex("com\\.android.*")
                    includeGroupByRegex("com\\.google\\.android.*")
                    includeGroupByRegex("androidx.*")
                }
            }
            maven("$localMirror/central") { isAllowInsecureProtocol = true }
            maven("$localMirror/jitpack") { isAllowInsecureProtocol = true }
        }
    }
}

rootProject.name = "v2rayNG"
include(":app")
