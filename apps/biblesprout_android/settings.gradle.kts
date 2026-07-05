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
        // The Onyx BOOX SDK (added later for e-ink refresh control) lives here.
        maven {
            url = uri("http://repo.boox.com/repository/maven-public/")
            isAllowInsecureProtocol = true
        }
        maven { url = uri("https://jitpack.io") }
    }
}

rootProject.name = "biblesprout_android"
include(":app")
