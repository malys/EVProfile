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

rootProject.name = "EVProfile"
include(":app")

// Shared vehicle layer, vendored as a git submodule at ./EVHardware and consumed as a
// Gradle subproject. One implementation of EVHardware/VehicleWriteGate/FirmwareInfo/models.
include(":evhardware")
project(":evhardware").projectDir = file("EVHardware/lib")
