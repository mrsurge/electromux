package dev.mrsurge.electromux.node

/** Immutable APK-owned entry/policy. Never construct this from a page request. */
class EmbeddedConsumerSpec(entryAsset: String, methods: Set<String>, events: Set<String>) {
    val entryAsset = entryAsset
    val methods: Set<String> = java.util.Collections.unmodifiableSet(LinkedHashSet(methods))
    val events: Set<String> = java.util.Collections.unmodifiableSet(LinkedHashSet(events))
    init {
        require(Regex("embedded_node/[A-Za-z0-9_-]+\\.mjs").matches(entryAsset))
        require(this.methods.isNotEmpty() && this.methods.size <= 64)
        require(this.events.size <= 64 && "runtime.ready" !in this.events)
        require((this.methods + this.events).all { Regex("[A-Za-z][A-Za-z0-9_.-]{0,127}").matches(it) })
    }
    companion object {
        fun proof(termux: Boolean) = EmbeddedConsumerSpec("embedded_node/main.mjs",
            setOf("ping", "file.proof") + if (termux) setOf("child.proof", "child.cancelProof", "child.start", "child.status", "child.stop") else emptySet(),
            setOf("sample.updated", "child.state", "child.output"))
    }
}
