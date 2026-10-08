plugins { id("com.android.library") }

val sdkRoot = providers.environmentVariable("ELECTROMUX_NODE_SDK").orNull
    ?: error("Set ELECTROMUX_NODE_SDK to the checksum-verified Node 24 Android SDK")
val ndkRoot = providers.environmentVariable("ELECTROMUX_NDK_HOME").orNull
    ?: error("Set ELECTROMUX_NDK_HOME to the installed Android NDK")
val consumerProvidesEntry = rootProject.name == "te2-termux"
val runtimeAssets = tasks.register<Sync>("bundleRuntimeProof") {
    val bundle = file("../../runtime/dist/main.mjs")
    doFirst { check(bundle.isFile) { "Run npm run typecheck && npm run build in runtime/ first" } }
    from(bundle) { into("embedded_node") }
    into(layout.buildDirectory.dir("generated/runtimeAssets"))
}
val transportTests = tasks.register<Sync>("reuseTransportTests") {
    from("../host/src/test/java") {
        include("**/FrameCodecTest.kt", "**/FramedTransportTest.kt")
    }
    into(layout.buildDirectory.dir("generated/transportTests"))
}
android {
    namespace = "dev.mrsurge.electromux.node"
    compileSdk = 37
    ndkVersion = "28.0.13004108"
    ndkPath = ndkRoot
    buildFeatures { aidl = true }
    defaultConfig {
        minSdk = 29
        ndk { abiFilters += "arm64-v8a" }
        externalNativeBuild { cmake {
            arguments += listOf("-DNODE_SDK_ROOT=$sdkRoot", "-DANDROID_STL=c++_shared")
            cppFlags += listOf("-std=c++20", "-Wall", "-Wextra", "-Werror")
        } }
    }
    externalNativeBuild { cmake { path = file("src/main/cpp/CMakeLists.txt") } }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
androidComponents.onVariants { variant ->
    if (!consumerProvidesEntry) variant.sources.assets?.addStaticSourceDirectory(layout.buildDirectory.dir("generated/runtimeAssets").get().asFile.absolutePath)
    variant.hostTests.values.forEach { test ->
        test.sources.java?.addStaticSourceDirectory(layout.buildDirectory.dir("generated/transportTests").get().asFile.absolutePath)
    }
}
tasks.named("preBuild") {
    dependsOn(transportTests)
    if (!consumerProvidesEntry) dependsOn(runtimeAssets)
}
dependencies {
    implementation(project(if (consumerProvidesEntry) ":electromux-host" else ":host"))
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20090211")
}
