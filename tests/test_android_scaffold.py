from pathlib import Path
import unittest
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
ANDROID = "{http://schemas.android.com/apk/res/android}"


class AndroidScaffoldTests(unittest.TestCase):
    def test_internal_host_library_is_consumer_independent(self) -> None:
        host = ROOT / "android/host"
        build = (host / "build.gradle.kts").read_text()
        self.assertIn('id("com.android.library")', build)
        self.assertNotIn("com.cefrium", build)
        self.assertNotIn("sample_backend.py", build)
        self.assertNotIn("applicationId", build)
        manifest = ET.parse(host / "src/main/AndroidManifest.xml").getroot()
        self.assertIsNone(manifest.find("application"))
        for source in (host / "src/main/java").rglob("*.kt"):
            text = source.read_text()
            self.assertNotIn("dev.mrsurge.electromux.sample", text, source.name)
            self.assertNotIn("com.cefrium", text, source.name)
        self.assertIn('implementation(project(":host"))',
                      (ROOT / "android/sample/build.gradle.kts").read_text())
        client = (host / "src/main/java/dev/mrsurge/electromux/host/TermuxHelperClient.kt").read_text()
        self.assertIn("fun requestBackend(payload: JSONObject)", client)
        self.assertNotIn('method == "ping"', client)

    def test_separate_sample_identity_and_root_uid(self) -> None:
        manifest = ET.parse(ROOT / "android/sample/src/main/AndroidManifest.xml").getroot()
        self.assertEqual(manifest.attrib[ANDROID + "sharedUserId"], "com.termux")
        self.assertNotIn(ANDROID + "sharedUserId", manifest.find("application").attrib)
        build = (ROOT / "android/sample/build.gradle.kts").read_text()
        self.assertIn('applicationId = "dev.mrsurge.electromux.sample"', build)
        self.assertIn("Explicit Termux-compatible signing configuration is required", build)

    def test_sample_uses_bundled_restricted_bridge(self) -> None:
        html = (ROOT / "android/sample/src/main/assets/index.html").read_text()
        self.assertIn('src="sample.js"', html)
        self.assertIn("frame-src 'none'", html)
        self.assertIn('data-method="connect"', html)
        self.assertNotIn("srcdoc", html)

    def test_cefrium_bridge_registers_native_callback_target(self) -> None:
        activity = (ROOT / "android/sample/src/main/java/dev/mrsurge/electromux/sample/MainActivity.kt").read_text()
        self.assertIn("browser.setQueryHandler", activity)
        self.assertIn("browser.setOnLoadingStateChangedListener", activity)
        # Loading is deferred to the service callback. It can only run after
        # onCreate registers the Cefrium callback target and requests binding.
        self.assertLess(activity.index("browser.setOnLoadingStateChangedListener"),
                        activity.index("bound = bindService"))
        callback = activity[activity.index("override fun onServiceConnected"):activity.index("override fun onServiceDisconnected")]
        self.assertLess(callback.index("pageBridge.beginNavigation"), callback.index("browser.loadUrl"))

    def test_private_service_detaches_without_stopping_termux_backend(self) -> None:
        root = ROOT / "android/sample/src/main"
        manifest = ET.parse(root / "AndroidManifest.xml").getroot()
        service = manifest.find("application/service")
        self.assertEqual(service.attrib[ANDROID + "name"], ".SampleRuntimeService")
        self.assertEqual(service.attrib[ANDROID + "exported"], "false")
        source = (root / "java/dev/mrsurge/electromux/sample/SampleRuntimeService.kt").read_text()
        self.assertIn("START_NOT_STICKY", source)
        self.assertIn("Binder.getCallingUid() == Process.myUid()", source)
        self.assertIn("runtime.close()", source)
        self.assertNotIn('call("shutdown")', source)
        activity = (root / "java/dev/mrsurge/electromux/sample/MainActivity.kt").read_text()
        self.assertIn("unbindService(connection)", activity)
        self.assertNotIn("stopService", activity)
