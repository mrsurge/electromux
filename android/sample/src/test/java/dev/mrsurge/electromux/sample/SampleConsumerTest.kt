package dev.mrsurge.electromux.sample

import org.junit.Assert.*
import org.junit.Test

class SampleConsumerTest {
    @Test fun bridgeRequiresExactPageAndFixedMethods() {
        val descriptor = SampleConsumer.descriptor
        assertTrue(descriptor.allows(descriptor.entrypoint, "ping"))
        for (origin in listOf(null, "https://example.com", "file:///android_asset/other.html", descriptor.entrypoint + "?x")) {
            assertFalse(descriptor.allows(origin, "ping"))
        }
        assertFalse(descriptor.allows(descriptor.entrypoint, "execute"))
    }
}
