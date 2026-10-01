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
        google()
        mavenCentral()
    }
}

rootProject.name = "Hexis"
include(":app")
// :bridge — the Hexis Bridge SDK spine (Phase 0). Shared by the core app and, later, satellite
// addons. See docs/design/addon-bridge-and-voice.md.
include(":bridge")
// :voice-addon — the Hexis Voice satellite (Phase 1). A separate APK holding RECORD_AUDIO; depends
// on :bridge. Not a dependency of :app.
include(":voice-addon")
