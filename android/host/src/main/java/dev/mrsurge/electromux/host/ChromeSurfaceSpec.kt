package dev.mrsurge.electromux.host

import java.io.Closeable

/** Consumer-declared packaged chrome; rendering engines implement the attachment seam. */
class ChromeSurfaceSpec(
    val consumer: ConsumerDescriptor,
    val heightDp: Int,
    val placement: Placement = Placement.TOP,
) {
    enum class Placement { TOP, BOTTOM }
    init {
        require(heightDp in 32..128)
        require(consumer.routes.size == 1) { "Chrome has one exact packaged document" }
    }
    fun attach(host: ChromeSurfaceHost): Closeable = host.attach(this)
}

/** No Cefrium, framework, process or application semantics belong in this contract. */
fun interface ChromeSurfaceHost {
    fun attach(spec: ChromeSurfaceSpec): Closeable
}
