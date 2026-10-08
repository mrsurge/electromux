plugins { id("com.android.application"); id("com.cefrium") }
android {
    namespace = "dev.mrsurge.electromux.calculator"
    compileSdk = 37
    defaultConfig {
        applicationId = "dev.mrsurge.electromux.calculator"
        minSdk = 29
        targetSdk = 34
        versionCode = 1
        versionName = "0.0.1-calculator-proof"
        ndk { abiFilters += "arm64-v8a" }
    }
    packaging { jniLibs { useLegacyPackaging = true } }
    buildTypes {
        create("staging") {
            isDebuggable = false
            isMinifyEnabled = true
            isShrinkResources = true
            signingConfig = signingConfigs.getByName("debug")
            matchingFallbacks += listOf("release")
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    sourceSets.getByName("main").assets.srcDir("../../samples/electron-calculator/build/assets")
}
tasks.named("preBuild") { doFirst {
    check(file("../../samples/electron-calculator/build/assets/embedded_node/calculator.mjs").isFile) {
        "Build samples/electron-calculator assets first"
    }
} }
configurations.configureEach { exclude(group = "com.google.guava", module = "listenablefuture") }
dependencies {
    implementation(project(":host"))
    implementation(project(":node-runtime"))
    implementation("com.cefrium:cefrium-sdk:0.9.0")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20090211")
}
