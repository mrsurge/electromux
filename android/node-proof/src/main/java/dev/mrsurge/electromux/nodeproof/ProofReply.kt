package dev.mrsurge.electromux.nodeproof

import org.json.JSONObject

/** Checked exceptions do not cross Android Binder reliably. Return a DTO. */
internal fun proofReply(request: () -> String): String = try { request() }
catch (error: Exception) {
    var cause: Throwable = error
    repeat(8) { cause.cause?.let { cause = it } }
    JSONObject().put("error", cause.message ?: cause.javaClass.simpleName)
        .put("kind", "runtime_failure").toString()
}
