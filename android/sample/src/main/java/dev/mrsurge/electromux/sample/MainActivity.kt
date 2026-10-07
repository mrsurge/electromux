package dev.mrsurge.electromux.sample

import android.app.Activity
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import com.cefrium.CefriumBrowser

/** Independent bundled-page sample with native-owned authenticated helper controls. */
class MainActivity : Activity() {
    private lateinit var browser: CefriumBrowser
    private lateinit var pageBridge: SamplePageBridge

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        browser = CefriumBrowser.createWithSurface(this)
        browser.setPinchToZoomEnabled(false)
        pageBridge = SamplePageBridge(this) { script -> browser.evaluateJavaScript(script) }
        browser.setQueryHandler { _, request, origin, callback ->
            pageBridge.handle(request, origin, callback)
        }
        // Cefrium 0.9.0 setQueryHandler stores only the Java field. This public
        // listener also registers the browser's native callback/bridge target.
        var wasLoading = false
        browser.setOnLoadingStateChangedListener { loading, _, _ ->
            if (loading && !wasLoading) pageBridge.beginNavigation()
            wasLoading = loading
        }
        browser.setOnUrlChangedListener { url -> pageBridge.changePage(url) }
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
        pageBridge.beginNavigation()
        browser.loadUrl(SampleConsumer.descriptor.entrypoint)
    }

    override fun onDestroy() {
        DiagnosticCompletion.observe(null)
        if (::pageBridge.isInitialized) pageBridge.close()
        if (::browser.isInitialized) browser.close()
        super.onDestroy()
    }
}
