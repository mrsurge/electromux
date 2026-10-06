from pathlib import Path
import unittest
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
ANDROID = "{http://schemas.android.com/apk/res/android}"


class AndroidScaffoldTests(unittest.TestCase):
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
        self.assertLess(activity.index("browser.setOnLoadingStateChangedListener"),
                        activity.index("browser.loadUrl"))
