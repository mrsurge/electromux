plugins { id("com.android.application") }
val termuxLane = project.name == "node-termux-proof"
val keyPath = providers.environmentVariable("ELECTROMUX_KEYSTORE").orNull
android {
    namespace = "dev.mrsurge.electromux.nodeproof"
    compileSdk = 37
    buildFeatures { aidl = true; buildConfig = true }
    defaultConfig {
        applicationId = if (termuxLane) "dev.mrsurge.electromux.nodeproof.termux" else "dev.mrsurge.electromux.nodeproof"
        buildConfigField("boolean", "TERMUX_PROOF", termuxLane.toString())
        minSdk = 29
        targetSdk = 34
        versionCode = 1
        versionName = "0.0.1-node-proof"
        ndk { abiFilters += "arm64-v8a" }
    }
    if (termuxLane && keyPath != null) {
        signingConfigs {
            create("termuxCompatible") {
                storeFile = file(keyPath)
                storePassword = providers.environmentVariable("ELECTROMUX_STORE_PASSWORD").get()
                keyAlias = providers.environmentVariable("ELECTROMUX_KEY_ALIAS").get()
                keyPassword = providers.environmentVariable("ELECTROMUX_KEY_PASSWORD").get()
            }
        }
        buildTypes.getByName("debug") { signingConfig = signingConfigs.getByName("termuxCompatible") }
    }
    packaging { jniLibs { useLegacyPackaging = false } }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
if (termuxLane) androidComponents.onVariants { variant ->
    variant.sources.java?.addStaticSourceDirectory(file("../node-proof/src/main/java").absolutePath)
    variant.sources.aidl?.addStaticSourceDirectory(file("../node-proof/src/main/aidl").absolutePath)
    variant.hostTests.values.forEach { test ->
        test.sources.java?.addStaticSourceDirectory(file("../node-proof/src/test/java").absolutePath)
    }
}
gradle.taskGraph.whenReady {
    if (termuxLane && keyPath == null && allTasks.any {
        it.project == project && it.name.matches(Regex("(assemble|package|bundle|sign|install).*"))
    }) error("Explicit matching Termux signing configuration required")
}
dependencies {
    implementation(project(":node-runtime"))
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20090211")
}
