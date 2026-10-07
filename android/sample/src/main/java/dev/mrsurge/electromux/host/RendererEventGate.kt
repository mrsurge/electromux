package dev.mrsurge.electromux.host

/** Document lifetime, not backend ownership. Access is safe across I/O/UI lanes. */
class RendererEventGate(routes: Set<String>) {
    private val routes = routes.toSet()
    data class Ticket(val generation: Long, val page: String, val documentId: String)
    private var generation = 0L
    private var page: String? = null
    private var ticket: Ticket? = null
    private var closed = false

    @Synchronized fun beginNavigation(): Long {
        generation++
        ticket = null
        return generation
    }

    @Synchronized fun changePage(next: String?) {
        if (page != next) beginNavigation()
        page = next
    }

    @Synchronized fun captureRequest(origin: String?): Long? =
        if (!closed && origin in routes && origin == page) generation else null

    @Synchronized fun bind(expected: Long, origin: String, documentId: String?) {
        check(current(expected, origin)) { "Renderer document changed" }
        if (documentId == null) return
        require(documentId.matches(Regex("[a-zA-Z0-9_-]{16,80}")))
        val next = Ticket(expected, origin, documentId)
        check(ticket == null || ticket == next) { "Renderer document identity changed without navigation" }
        ticket = next
    }

    @Synchronized fun current(expected: Long, origin: String): Boolean =
        !closed && expected == generation && page == origin && origin in routes

    @Synchronized fun captureEvent(): Ticket? = if (closed) null else ticket
    @Synchronized fun accepts(expected: Ticket): Boolean =
        ticket == expected && current(expected.generation, expected.page)

    @Synchronized fun close() { closed = true; beginNavigation() }
}
