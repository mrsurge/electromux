package dev.mrsurge.electromux.host

/** Native-owned declaration, never accepted from page input. APK identity is build-time. */
class ConsumerDescriptor(
    val id: String,
    val label: String,
    val entrypoint: String,
    assets: Set<String>,
    routes: Map<String, String>,
    methods: Set<String>,
    events: Set<String>,
) {
    val assets = assets.toSet()
    val routes = routes.toMap()
    val methods = methods.toSet()
    val events = events.toSet()

    init {
        require(id.matches(Regex("[a-zA-Z0-9_.-]{1,80}")) && label.isNotBlank())
        require(entrypoint in this.routes)
        require(this.assets.isNotEmpty() && this.assets.all(::validAsset))
        require(this.routes.keys.all { it.startsWith("file:///android_asset/") &&
            it == "file:///android_asset/" + this.routes.getValue(it) })
        require(this.routes.values.all { it in this.assets })
        require(this.methods.isNotEmpty())
        require((this.methods + this.events).all { it.matches(Regex("[a-zA-Z][a-zA-Z0-9_.-]{0,79}")) })
    }

    fun allows(page: String?, method: String): Boolean = page in routes && method in methods

    companion object {
        private fun validAsset(path: String): Boolean = path.isNotEmpty() &&
            !path.startsWith('/') && !path.contains('\\') &&
            path.split('/').all { it.isNotEmpty() && it != "." && it != ".." &&
                it.matches(Regex("[a-zA-Z0-9_.-]+")) }
    }
}
