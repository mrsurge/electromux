package dev.mrsurge.electromux.sample

import dev.mrsurge.electromux.host.RendererEventGate
import org.junit.Assert.*
import org.junit.Test

class RendererEventGateTest {
    private val page = "file:///android_asset/index.html"
    private val first = "document_first_123456"
    private val second = "document_second_123456"

    @Test fun requiresExactCurrentPageAndExplicitDocument() {
        val gate = RendererEventGate(setOf(page))
        assertNull(gate.captureRequest(page))
        gate.changePage(page)
        assertNull(gate.captureRequest("https://example.com"))
        val generation = gate.captureRequest(page)!!
        assertNull(gate.captureEvent())
        gate.bind(generation, page, first)
        assertTrue(gate.accepts(gate.captureEvent()!!))
    }

    @Test fun sameUrlReloadInvalidatesOldRequestsAndEvents() {
        val gate = RendererEventGate(setOf(page))
        gate.changePage(page)
        val before = gate.captureRequest(page)!!
        gate.bind(before, page, first)
        val event = gate.captureEvent()!!
        gate.beginNavigation()
        assertFalse(gate.current(before, page))
        assertFalse(gate.accepts(event))
        assertNull(gate.captureEvent())
        gate.bind(gate.captureRequest(page)!!, page, second)
        assertFalse(gate.accepts(event))
        assertEquals(second, gate.captureEvent()!!.documentId)
    }

    @Test fun cannotReplaceIdentityWithoutNavigation() {
        val gate = RendererEventGate(setOf(page))
        gate.changePage(page)
        val generation = gate.captureRequest(page)!!
        gate.bind(generation, page, first)
        try { gate.bind(generation, page, second); fail("replaced document") }
        catch (_: IllegalStateException) { }
        try { gate.bind(generation, page, "invalid"); fail("accepted invalid identity") }
        catch (_: IllegalArgumentException) { }
    }

    @Test fun navigationAndCloseRevokeAllDelivery() {
        val gate = RendererEventGate(setOf(page))
        gate.changePage(page)
        gate.bind(gate.captureRequest(page)!!, page, first)
        val event = gate.captureEvent()!!
        gate.changePage("https://example.com")
        assertFalse(gate.accepts(event))
        assertNull(gate.captureRequest(page))
        gate.changePage(page)
        gate.close()
        assertNull(gate.captureRequest(page))
        assertNull(gate.captureEvent())
    }
}
