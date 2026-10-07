package dev.mrsurge.electromux.sample

import dev.mrsurge.electromux.host.HelperInstallSpec
import dev.mrsurge.electromux.host.BundledBackendSpec
import org.junit.Assert.*
import org.junit.Test

class HelperInstallSpecTest {
    private val base = "/data/data/com.termux/files/home/.cache/consumer"
    @Test fun nativeAssetMapIsCopiedAndRejectsTraversal() {
        val files = mutableMapOf("electromux/helper.py" to "helper/helper.py")
        val spec = HelperInstallSpec(base, "consumer-session", files)
        files.clear()
        assertEquals(1, spec.files.size)
        for (bad in listOf("../escape", "/absolute", "x//y", "x/./y")) {
            try { HelperInstallSpec(base, "consumer", mapOf("electromux/helper.py" to bad)); fail(bad) }
            catch (_: IllegalArgumentException) { }
        }
        try { HelperInstallSpec("/sdcard/consumer", "consumer", spec.files); fail("external root") }
        catch (_: IllegalArgumentException) { }
    }
    @Test fun backendRequiresBundledEntrypointAndNativeExecutable() {
        val backend = BundledBackendSpec("/data/data/com.termux/files/usr/bin/node", "actor/main.cjs")
        try { HelperInstallSpec(base, "consumer", mapOf("electromux/helper.py" to "helper.py"), backend); fail("missing actor") }
        catch (_: IllegalArgumentException) { }
        try { BundledBackendSpec("/bin/sh", "actor.js"); fail("external executable") }
        catch (_: IllegalArgumentException) { }
        try { BundledBackendSpec("/data/data/com.termux/files/usr/bin/node", "actor.js", environment = mapOf("BAD=KEY" to "x")); fail("invalid environment") }
        catch (_: IllegalArgumentException) { }
    }
}
