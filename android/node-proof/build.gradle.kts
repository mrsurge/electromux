plugins { id("com.android.application") }
android {
    namespace = "dev.mrsurge.electromux.nodeproof"
    compileSdk = 37
    buildFeatures { aidl = true }
    defaultConfig {
        applicationId = "dev.mrsurge.electromux.nodeproof"
        minSdk = 29
        targetSdk = 34
        versionCode = 1
        versionName = "0.0.1-node-proof"
        ndk { abiFilters += "arm64-v8a" }
    }
    packaging { jniLibs { useLegacyPackaging = false } }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
dependencies {
    implementation(project(":node-runtime"))
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20090211")
}
