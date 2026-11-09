pluginManagement {
    repositories { google(); mavenCentral(); gradlePluginPortal() }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        providers.gradleProperty("offlineRepo").orNull?.let { maven { url = uri(it) } }
        google(); mavenCentral()
    }
}
rootProject.name = "RustEQ"
include(":app")
include(":session-player")

