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
// :whisper — whisper.cpp built from vendored source (CPU, arm64-v8a); the on-device STT engine.
include(":whisper")
// :voice-addon — the Hexis Voice satellite (Phase 1). A separate APK holding RECORD_AUDIO; depends
// on :bridge and :whisper. Not a dependency of :app.
include(":voice-addon")
// :web-bridge — the Hexis Web Bridge satellite. A separate APK holding INTERNET; runs a local web
// server so the core can be managed from a browser on the LAN. Consumes the core's `data` capability
// over :bridge; holds no data and not the DB key. Not a dependency of :app.
include(":web-bridge")
