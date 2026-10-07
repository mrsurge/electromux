package dev.mrsurge.electromux.host

/** Native consumer configuration only; never constructed from renderer input. */
class HelperInstallSpec(val basePath: String, val preferencesName: String, files: Map<String, String>,
                        val backend: BundledBackendSpec? = null) {
    val files = files.toMap()
    init {
        require(basePath.startsWith("/data/data/com.termux/files/home/") &&
            basePath.split('/').none { it == ".." || it == "." } && '\u0000' !in basePath)
        require(preferencesName.matches(Regex("[a-zA-Z0-9_-]{1,80}")))
        require(this.files.size in 1..64)
        this.files.forEach { (target, asset) -> require(relative(target) && relative(asset)) }
        require("electromux/helper.py" in this.files)
        require(backend == null || backend.entrypoint in this.files)
    }
    companion object {
        fun relative(path: String): Boolean = path.isNotEmpty() && !path.startsWith('/') &&
            '\u0000' !in path && path.split('/').all { it.isNotEmpty() && it != "." && it != ".." }
    }
}

class BundledBackendSpec(val executable: String, val entrypoint: String,
                         arguments: List<String> = emptyList(), environment: Map<String, String> = emptyMap(),
                         val stopTimeout: Double = 2.0) {
    val arguments = arguments.toList()
    val environment = environment.toMap()
    init {
        require(executable.startsWith("/data/data/com.termux/files/usr/bin/") &&
            executable.split('/').none { it == ".." || it == "." } && '\u0000' !in executable)
        require(HelperInstallSpec.relative(entrypoint))
        require(this.arguments.size <= 126 && this.arguments.all { '\u0000' !in it && it.length <= 16384 })
        require(this.environment.size <= 64 && this.environment.all { (k, v) ->
            k.isNotEmpty() && '=' !in k && '\u0000' !in k && '\u0000' !in v && v.length <= 16384
        })
        require(stopTimeout > 0 && stopTimeout <= 30)
    }
}
