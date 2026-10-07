package dev.mrsurge.electromux.sample

import dev.mrsurge.electromux.host.ConsumerDescriptor
import org.junit.Assert.*
import org.junit.Test

class ConsumerDescriptorTest {
    @org.junit.Test fun nativeLoopbackOriginStillRequiresExactDeclaredDocument() {
        val page = "http://127.0.0.1:32123/shell/index.html"
        val descriptor = dev.mrsurge.electromux.host.ConsumerDescriptor("other", "Other", page,
            setOf("shell/index.html"), mapOf(page to "shell/index.html"), setOf("start"), emptySet(),
            "http://127.0.0.1:32123")
        org.junit.Assert.assertTrue(descriptor.allows(page, "start"))
        for (bad in listOf(page + "?x", page + "#x", page.replace("32123", "32124"),
            "http://127.0.0.1:32123/app/remote")) org.junit.Assert.assertFalse(descriptor.allows(bad, "start"))
        for (bad in listOf("http://localhost:32123", "https://127.0.0.1:32123", "http://127.0.0.1:32123/")) {
            try { dev.mrsurge.electromux.host.ConsumerDescriptor("other", "Other", page,
                setOf("shell/index.html"), mapOf(page to "shell/index.html"), setOf("start"), emptySet(), bad)
                org.junit.Assert.fail("untrusted origin")
            } catch (_: IllegalArgumentException) { }
        }
    }
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
