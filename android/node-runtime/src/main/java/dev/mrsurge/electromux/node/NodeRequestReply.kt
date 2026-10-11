package dev.mrsurge.electromux.node

import org.json.JSONObject

/** Checked Future/transport failures cannot escape Android's Binder dispatch. */
internal fun nodeRequestReply(operation: () -> String): String = try { operation() }
catch (failure: Exception) {
    JSONObject().put("error", (failure.message ?: "Node consumer request failed").take(2048)).toString()
}
