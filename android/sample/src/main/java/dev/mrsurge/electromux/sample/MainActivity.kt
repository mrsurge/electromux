package dev.mrsurge.electromux.sample

import android.app.Activity
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import com.cefrium.CefriumBrowser

/** Native diagnostic execution only; backend helper/page bridge wiring remains pending. */
class MainActivity : Activity() {
    private lateinit var browser: CefriumBrowser

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        browser = CefriumBrowser.createWithSurface(this)
        browser.setPinchToZoomEnabled(false)
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
                    status.text = "Diagnostic execution dispatched. Completion is not yet observed."
                } catch (_: Exception) {
                    status.text = "Execution refused or unavailable. Check Termux identity and device logs."
                }
            }
        }
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(status)
            addView(inspect)
            addView(execute)
            addView(browser.surfaceContainer, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        }
        status.text = adapter.inspect().summary()
        setContentView(layout)
        // Placeholder asset URL. Prove Cefrium's supported local hosting API
        // before claiming stable-origin module/worker/bridge behavior.
        browser.loadUrl("file:///android_asset/index.html")
    }

    override fun onDestroy() {
        if (::browser.isInitialized) browser.close()
        super.onDestroy()
    }
}
