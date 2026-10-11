package dev.mrsurge.electromux.nodeproof

import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.IBinder
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/** Native diagnostic UI first; Cefrium renderer integration is a later gate. */
class MainActivity : Activity() {
    private var remote: INodeProof? = null
    private var bound = false
    private var destroyed = false
    private lateinit var status: TextView
    private val lane = ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS, ArrayBlockingQueue(4))
    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            remote = INodeProof.Stub.asInterface(service)
            status.text = "Service connected. Ping tests embedded Node readiness."
        }
        override fun onServiceDisconnected(name: ComponentName?) {
            remote = null; status.text = "Node service disconnected; no automatic request replay"
        }
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        status = TextView(this).apply { text = "Binding embedded Node service" }
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(status)
            val actions = mutableListOf("Ping Node" to "ping", "Filesystem proof" to "file.proof")
            if (BuildConfig.TERMUX_PROOF) actions.addAll(listOf(
                "Termux child / FD3 proof" to "child.proof", "Termux group cancellation" to "child.cancelProof",
                "Start owned service" to "child.start", "Service status" to "child.status", "Stop owned service" to "child.stop"))
            for ((title, method) in actions) {
                addView(Button(this@MainActivity).apply {
                    text = title
                    setOnClickListener {
                        val target = remote
                        if (target == null) { status.text = "Service unavailable"; return@setOnClickListener }
                        isEnabled = false
                        lane.execute {
                            val result = try { target.request(method) + "\nEvent: " + target.lastEvent() }
                                catch (error: Exception) { "Proof failed: ${error.message}" }
                            runOnUiThread { if (!destroyed) { status.text = result; isEnabled = true } }
                        }
                    }
                })
            }
        }
        setContentView(layout)
        val runtimeIntent = Intent(this, NodeProofService::class.java)
        startService(runtimeIntent)
        bound = bindService(runtimeIntent, connection, BIND_AUTO_CREATE)
    }
    override fun onDestroy() {
        destroyed = true; lane.shutdownNow()
        if (bound) unbindService(connection)
        super.onDestroy()
    }
}
