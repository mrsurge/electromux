package dev.mrsurge.electromux.sample

import dev.mrsurge.electromux.host.*
import java.io.Closeable
import org.junit.Assert.*
import org.junit.Test

class ChromeSurfaceSpecTest {
    private fun descriptor() = ConsumerDescriptor("sample.chrome", "Sample toolbar",
        "file:///android_asset/chrome.html", setOf("chrome.html"),
        mapOf("file:///android_asset/chrome.html" to "chrome.html"),
        setOf("view_action", "get_chrome_state"), setOf("chrome-state"))

    @Test fun apiDeclaresAttachmentAndDisposalWithoutApplicationDependency() {
        val spec = ChromeSurfaceSpec(descriptor(), 48)
        var attached: ChromeSurfaceSpec? = null
        var closed = false
        val handle = spec.attach(ChromeSurfaceHost { declared ->
            attached = declared
            Closeable { closed = true }
        })
        assertSame(spec, attached)
        assertEquals(ChromeSurfaceSpec.Placement.TOP, spec.placement)
        assertFalse(spec.consumer.allows("file:///android_asset/app.html", "view_action"))
        handle.close()
        assertTrue(closed)
    }

    @Test fun rejectsUnboundedGeometry() {
        for (height in listOf(0, 31, 129, 9999)) {
            try { ChromeSurfaceSpec(descriptor(), height); fail("invalid height") }
            catch (_: IllegalArgumentException) {}
        }
    }
}
