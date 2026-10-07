package dev.mrsurge.electromux.sample

import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import com.cefrium.CefriumBrowser
import dev.mrsurge.electromux.host.LaunchSpec

/** Independent bundled-page sample with native-owned authenticated helper controls. */
class MainActivity : Activity() {
    private lateinit var browser: CefriumBrowser
    private lateinit var pageBridge: SamplePageBridge
    private var bound = false
    private var destroyed = false
    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            if (destroyed) return
            val service = (binder as SampleRuntimeService.LocalBinder).service()
            if (::pageBridge.isInitialized) pageBridge.close()
            pageBridge = SamplePageBridge(service) { script -> browser.evaluateJavaScript(script) }
            pageBridge.beginNavigation()
            browser.loadUrl(SampleConsumer.descriptor.entrypoint)
        }
        override fun onServiceDisconnected(name: ComponentName?) {
            if (::pageBridge.isInitialized) pageBridge.close()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        browser = CefriumBrowser.createWithSurface(this)
        browser.setPinchToZoomEnabled(false)
        browser.setQueryHandler { _, request, origin, callback ->
            if (::pageBridge.isInitialized) pageBridge.handle(request, origin, callback)
            else { callback.failure(503, "Runtime not connected"); true }
        }
        // Cefrium 0.9.0 setQueryHandler stores only the Java field. This public
        // listener also registers the browser's native callback/bridge target.
        var wasLoading = false
        browser.setOnLoadingStateChangedListener { loading, _, _ ->
            if (loading && !wasLoading && ::pageBridge.isInitialized) pageBridge.beginNavigation()
            wasLoading = loading
        }
        browser.setOnUrlChangedListener { url -> if (::pageBridge.isInitialized) pageBridge.changePage(url) }
        val adapter = TermuxLaunchAdapter(this)
        val status = TextView(this)
        val inspect = Button(this).apply {
            text = "Check Termux identity"
            setOnClickListener { status.text = adapter.inspect().summary() }
        }
        val execute = Button(this).apply {
            text = "Test Termux execution"
            setOnClickListener {
                try {
                    adapter.launch(LaunchSpec("/data/data/com.termux/files/usr/bin/python",
                        listOf("--version"), "/data/data/com.termux/files/home"))
                } catch (_: Exception) {
                    status.text = "Execution refused or unavailable. Check Termux identity and device logs."
                }
            }
        }
        DiagnosticCompletion.observe {
            status.text = DiagnosticCompletion.state.message ?: adapter.inspect().summary()
            execute.isEnabled = DiagnosticCompletion.state.pendingId == null
        }
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(status)
            addView(inspect)
            addView(execute)
            addView(browser.surfaceContainer, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        }
        status.text = DiagnosticCompletion.state.message ?: adapter.inspect().summary()
        execute.isEnabled = DiagnosticCompletion.state.pendingId == null
        setContentView(layout)
        // Placeholder asset URL. Prove Cefrium's supported local hosting API
        // before claiming stable-origin module/worker/bridge behavior.
        val runtimeIntent = Intent(this, SampleRuntimeService::class.java)
        startService(runtimeIntent)
        bound = bindService(runtimeIntent, connection, BIND_AUTO_CREATE)
        if (!bound) status.text = "Runtime binding unavailable"
    }

    override fun onDestroy() {
        destroyed = true
        DiagnosticCompletion.observe(null)
        if (::pageBridge.isInitialized) pageBridge.close()
        if (bound) { unbindService(connection); bound = false }
        if (::browser.isInitialized) browser.close()
        super.onDestroy()
    }
}
