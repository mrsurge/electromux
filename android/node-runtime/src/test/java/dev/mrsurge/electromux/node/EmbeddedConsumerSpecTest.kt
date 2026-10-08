package dev.mrsurge.electromux.node

import org.junit.Assert.*
import org.junit.Test

class EmbeddedConsumerSpecTest {
    @Test fun copiesNativeDeclarations() {
        val methods = mutableSetOf("ping")
        val events = mutableSetOf("state")
        val spec = EmbeddedConsumerSpec("embedded_node/consumer.mjs", methods, events)
        methods.add("spawn"); events.clear()
        assertEquals(setOf("ping"), spec.methods)
        assertEquals(setOf("state"), spec.events)
    }
    @Test fun rejectsPagePathsAndReservedEvents() {
        for (path in listOf("../main.mjs", "https://remote/main.mjs", "embedded_node/../main.mjs")) {
            try { EmbeddedConsumerSpec(path, setOf("ping"), emptySet()); fail(path) }
            catch (_: IllegalArgumentException) {}
        }
        try { EmbeddedConsumerSpec("embedded_node/main.mjs", setOf("ping"), setOf("runtime.ready")); fail() }
        catch (_: IllegalArgumentException) {}
    }
    @Test fun resourcesAreCopiedAndConfined() {
        val resources = mutableSetOf("electron_app/app.js")
        val spec = EmbeddedConsumerSpec("embedded_node/main.mjs", setOf("ping"), emptySet(), resources)
        resources.clear(); assertEquals(setOf("electron_app/app.js"), spec.resources)
        for (path in listOf("../app.js", "electron_app/../app.js", "electron_app//app.js", "/electron_app/app.js")) {
            try { EmbeddedConsumerSpec("embedded_node/main.mjs", setOf("ping"), emptySet(), setOf(path)); fail(path) }
            catch (_: IllegalArgumentException) {}
        }
    }
}
