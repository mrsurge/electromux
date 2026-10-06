package dev.mrsurge.electromux.sample

/** Process-local, single-flight diagnostic. No uncertain command retry. */
class DiagnosticState {
    var pendingId: String? = null
        private set
    var message: String? = null
        private set

    fun begin(id: String) {
        check(pendingId == null) { "Diagnostic already pending" }
        pendingId = id
        message = "Diagnostic dispatched; awaiting completion."
    }

    fun finish(id: String, result: String): Boolean {
        if (pendingId != id) return false
        pendingId = null
        message = result
        return true
    }
}

data class DiagnosticResult(val exitCode: Int?, val error: Int?, val stdout: String,
    val stderr: String, val errorMessage: String) {
    fun summary(): String = "Diagnostic completed: exit=${exitCode ?: "missing"}, error=${error ?: "missing"}" +
        "\nstdout: ${bounded(stdout)}\nstderr: ${bounded(stderr)}" +
        if (errorMessage.isEmpty()) "" else "\nerror: ${bounded(errorMessage)}"

    private fun bounded(text: String): String = if (text.length <= 4096) text else
        text.take(4096) + "\n[truncated]"
}
