pluginManagement {
    repositories {
        google()
        // Google's mirror of Maven Central (avoids Central rate limits on shared CI egress).
        maven("https://maven-central.storage-download.googleapis.com/maven2/")
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        // Google's mirror of Maven Central (avoids Central rate limits on shared CI egress).
        maven("https://maven-central.storage-download.googleapis.com/maven2/")
        mavenCentral()
    }
}

rootProject.name = "parley-phone"
include(":app", ":core:common", ":core:data", ":core:ui", ":telecom")
