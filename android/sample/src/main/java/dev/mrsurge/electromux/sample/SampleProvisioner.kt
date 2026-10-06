package dev.mrsurge.electromux.sample

import android.content.Context
import android.system.Os
import java.io.File
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.UUID

data class HelperSession(val root: File, val packageRoot: File, val id: String, val token: String) {
    val socketPath: String get() = File(root, "host.sock").path
    fun launchSpec() = LaunchSpec("/data/data/com.termux/files/usr/bin/python",
        listOf("-B", "-m", "electromux.helper", "--socket", socketPath, "--session", id,
            "--token-file", File(root, "token").path), packageRoot.path)
}

/** Fixed sample-owned paths only. No pip/global install or page-provided paths. */
class SampleProvisioner(private val context: Context) {
    private val base = File("/data/data/com.termux/files/home/.cache/electromux-sample")
    private val prefs = context.getSharedPreferences("helper-session", Context.MODE_PRIVATE)

    fun prepare(): HelperSession {
        privateDirectory(base)
        val names = listOf("__init__.py", "protocol.py", "helper.py", "sample_backend.py")
        val contents = names.associateWith { context.assets.open("helper/electromux/$it").use { it.readBytes() } }
        val digest = MessageDigest.getInstance("SHA-256")
        names.forEach { digest.update(it.toByteArray()); digest.update(contents.getValue(it)) }
        val hash = digest.digest().joinToString("") { "%02x".format(it) }.take(16)
        val packageRoot = File(base, "p/$hash")
        privateDirectory(File(packageRoot, "electromux"))
        names.forEach { name ->
            val file = File(packageRoot, "electromux/$name")
            check(file.canonicalFile.parentFile == File(packageRoot, "electromux").canonicalFile)
            if (file.exists()) check(file.readBytes().contentEquals(contents.getValue(name)))
            else { file.writeBytes(contents.getValue(name)); Os.chmod(file.path, 0x180) }
        }
        var id = prefs.getString("id", null)
        if (id == null) {
            id = UUID.randomUUID().toString().replace("-", "").take(12)
            check(prefs.edit().putString("id", id).commit())
        }
        require(id.matches(Regex("[a-f0-9]{12}")))
        val root = File(base, "s/$id")
        privateDirectory(root)
        val tokenFile = File(root, "token")
        check(tokenFile.canonicalFile.parentFile == root.canonicalFile)
        if (!tokenFile.exists()) {
            check(!attempted()) { "Lost session credentials; refusing uncertain relaunch" }
            val bytes = ByteArray(32).also { SecureRandom().nextBytes(it) }
            tokenFile.writeText(bytes.joinToString("") { "%02x".format(it) })
            Os.chmod(tokenFile.path, 0x180)
        }
        val token = tokenFile.readText()
        require(token.matches(Regex("[a-f0-9]{64}")))
        return HelperSession(root, packageRoot, id, token)
    }

    fun attempted(): Boolean = prefs.getBoolean("attempted", false)
    fun markAttempted(value: Boolean) { check(prefs.edit().putBoolean("attempted", value).commit()) }
    private fun privateDirectory(file: File) {
        val relative = file.absolutePath.removePrefix(base.absolutePath).trimStart('/')
        val expected = File(base.canonicalFile, relative).absoluteFile
        check(file.canonicalFile == expected) { "Unexpected managed path link" }
        check(file.isDirectory || file.mkdirs())
        Os.chmod(file.path, 0x1c0)
    }
}
