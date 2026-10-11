package dev.mrsurge.electromux.node

/** Retain one engine (or its startup failure) for the entire process lifetime.
 * Service/Activity destruction must not reset a single-start native runtime.
 */
class ProcessRuntimeSlot<T : Any> {
    private var outcome: Result<T>? = null
    @Synchronized fun acquire(factory: () -> T): T {
        val retained = outcome
        if (retained != null) return retained.getOrThrow()
        val created = runCatching(factory)
        outcome = created
        return created.getOrThrow()
    }
}
