package dev.mrsurge.electromux.sample

import android.app.Activity
import android.os.Bundle
import com.cefrium.CefriumBrowser

/** Browser scaffold only. Termux launch and trusted bridge wiring are the next gate. */
class MainActivity : Activity() {
    private lateinit var browser: CefriumBrowser

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        browser = CefriumBrowser.createWithSurface(this)
        browser.setPinchToZoomEnabled(false)
        setContentView(browser.surfaceContainer)
        // Placeholder asset URL. Prove Cefrium's supported local hosting API
        // before claiming stable-origin module/worker/bridge behavior.
        browser.loadUrl("file:///android_asset/index.html")
    }

    override fun onDestroy() {
        if (::browser.isInitialized) browser.close()
        super.onDestroy()
    }
}
