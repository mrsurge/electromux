plugins { id("com.android.library") }

val helperAssets = tasks.register<Sync>("bundleHelper") {
    from("../../electromux") {
        include("__init__.py", "protocol.py", "helper.py")
        into("helper/electromux")
    }
    into(layout.buildDirectory.dir("generated/helperAssets"))
}

android {
    namespace = "dev.mrsurge.electromux.host"
    compileSdk = 37
    defaultConfig { minSdk = 29 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
// AGP 9's library source-set DSL differs from its application source-set DSL.
// Register the generated directory through the variant API instead of casting
// the legacy source-set implementation; preBuild owns materialization below.
androidComponents.onVariants { variant ->
    variant.sources.assets?.addStaticSourceDirectory(
        layout.buildDirectory.dir("generated/helperAssets").get().asFile.absolutePath)
}
tasks.named("preBuild") { dependsOn(helperAssets) }

dependencies {
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20090211")
}
