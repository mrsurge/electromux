plugins {
    id("com.android.application")
    id("com.cefrium")
}

val keyPath = providers.environmentVariable("ELECTROMUX_KEYSTORE").orNull
android {
    namespace = "dev.mrsurge.electromux.sample"
    compileSdk = 37
    defaultConfig {
        applicationId = "dev.mrsurge.electromux.sample"
        minSdk = 29
        targetSdk = 34
        versionCode = 1
        versionName = "0.0.1-scaffold"
        ndk { abiFilters += "arm64-v8a" }
    }
    if (keyPath != null) {
        signingConfigs {
            create("termuxCompatible") {
                storeFile = file(keyPath)
                storePassword = providers.environmentVariable("ELECTROMUX_STORE_PASSWORD").get()
                keyAlias = providers.environmentVariable("ELECTROMUX_KEY_ALIAS").get()
                keyPassword = providers.environmentVariable("ELECTROMUX_KEY_PASSWORD").get()
            }
        }
        buildTypes {
            getByName("debug") { signingConfig = signingConfigs.getByName("termuxCompatible") }
            getByName("release") { signingConfig = signingConfigs.getByName("termuxCompatible") }
        }
    }
    packaging { jniLibs { useLegacyPackaging = true } }
}

gradle.taskGraph.whenReady {
    if (keyPath == null && allTasks.any {
        it.project == project && (it.name.startsWith("assemble") ||
            it.name.startsWith("package") || it.name.startsWith("install"))
    }) error("Explicit Termux-compatible signing configuration is required; see README.md")
}

dependencies {
    implementation("com.cefrium:cefrium-sdk:0.9.0")
}
