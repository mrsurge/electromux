package dev.mrsurge.electromux.sample

import dev.mrsurge.electromux.host.ConsumerDescriptor
import org.junit.Assert.*
import org.junit.Test

class ConsumerDescriptorTest {
    private fun make(asset: String = "index.html", route: String = "file:///android_asset/index.html") =
        ConsumerDescriptor("other.consumer", "Other app", route, setOf(asset),
            mapOf(route to asset), setOf("custom.action"), setOf("custom.updated"))

    @Test fun unrelatedConsumerUsesSameHostContract() {
        val other = make()
        assertTrue(other.allows(other.entrypoint, "custom.action"))
        assertFalse(other.allows(other.entrypoint, "start"))
    }
    @Test fun rejectsEscapesAndRemotePrivilege() {
        for (asset in listOf("../index.html", "/index.html", "a/../index.html", "a%2findex.html")) {
            try { make(asset); fail("accepted invalid asset") } catch (_: IllegalArgumentException) { }
        }
        try { make(route = "https://example.com/index.html"); fail("trusted remote page") }
        catch (_: IllegalArgumentException) { }
    }
}
