package dev.mrsurge.electromux.sample

import android.app.Application
import android.content.Context
import org.chromium.base.CommandLine

class SampleApplication : Application() {
    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(base)
        if (!CommandLine.isInitialized()) CommandLine.init(null)
        CommandLine.getInstance().appendSwitchWithValue("javaless-renderers", "disabled")
    }
}
