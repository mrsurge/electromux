package dev.mrsurge.electromux.sample

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import java.util.UUID

/** Main-thread ownership; survives Activity recreation, not process death. */
object DiagnosticCompletion {
    const val ACTION = "dev.mrsurge.electromux.sample.DIAGNOSTIC_RESULT"
    val state = DiagnosticState()
    private val handler = Handler(Looper.getMainLooper())
    private var callback: PendingIntent? = null
    private var timeout: Runnable? = null
    private var observer: (() -> Unit)? = null

    fun observe(listener: (() -> Unit)?) { observer = listener }

    fun prepare(context: Context): PendingIntent {
        check(state.pendingId == null) { "Diagnostic already pending" }
        val id = UUID.randomUUID().toString()
        val intent = Intent(context, DiagnosticResultReceiver::class.java).apply {
            action = ACTION
            data = Uri.Builder().scheme("electromux-diagnostic").authority("result").appendPath(id).build()
        }
        // Termux must add the result Bundle. The explicit component/action/data
        // are fixed; mutability is not permission to redirect another Intent.
        val flags = PendingIntent.FLAG_ONE_SHOT or
            if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0
        val pending = PendingIntent.getBroadcast(context, 0, intent, flags)
        state.begin(id)
        callback = pending
        timeout = Runnable { finish(id, "Diagnostic timed out; completion is unknown. No retry performed.") }
        handler.postDelayed(timeout!!, 30_000)
        observer?.invoke()
        return pending
    }

    fun accepts(id: String): Boolean = state.pendingId == id

    fun finish(id: String, message: String) {
        if (!state.finish(id, message)) return
        timeout?.let { handler.removeCallbacks(it) }
        timeout = null
        callback?.cancel()
        callback = null
        observer?.invoke()
    }

    fun dispatchFailed() {
        state.pendingId?.let { finish(it, "Execution refused or unavailable. Check Termux identity and device logs.") }
    }
}

/** Non-exported result-only receiver: never launches or forwards anything. */
class DiagnosticResultReceiver : BroadcastReceiver() {
    @Suppress("DEPRECATION")
    override fun onReceive(context: Context, intent: Intent) {
        val uri = intent.data ?: return
        if (intent.action != DiagnosticCompletion.ACTION || uri.scheme != "electromux-diagnostic" ||
            uri.authority != "result" || uri.pathSegments.size != 1) return
        val id = uri.lastPathSegment ?: return
        if (!DiagnosticCompletion.accepts(id)) return
        try {
            val bundle = intent.getBundleExtra("result")
            val message = if (bundle == null) "Diagnostic callback missing result bundle." else {
                DiagnosticResult(
                    bundle.get("exitCode") as? Int,
                    bundle.get("err") as? Int,
                    bundle.getString("stdout").orEmpty(), bundle.getString("stderr").orEmpty(),
                    bundle.getString("errmsg").orEmpty()).summary()
            }
            DiagnosticCompletion.finish(id, message)
        } catch (_: RuntimeException) {
            DiagnosticCompletion.finish(id, "Diagnostic callback contained an invalid result bundle.")
        }
    }
}
