pluginManagement {
    repositories {
        google()
        mavenCentral()
        maven("https://codeberg.org/api/packages/cefrium/maven")
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        maven("https://codeberg.org/api/packages/cefrium/maven")
    }
}
rootProject.name = "electromux-sample"
include(":sample")
include(":host")
if (providers.gradleProperty("electromuxCalculator").orNull == "true") {
    include(":node-runtime", ":calculator")
}
// Opt-in proof only: accepted host/TE2 consumers keep their current runtime.
if (providers.gradleProperty("electromuxEmbeddedNodeProof").orNull == "true") {
    include(":node-runtime", ":node-proof")
    if (providers.gradleProperty("electromuxTermuxProof").orNull == "true") {
        include(":node-termux-proof")
        project(":node-termux-proof").buildFileName = "../node-proof/build.gradle.kts"
    }
}
