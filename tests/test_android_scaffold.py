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

    def test_sample_does_not_claim_live_bridge(self) -> None:
        html = (ROOT / "android/sample/src/main/assets/index.html").read_text()
        self.assertIn("not wired yet", html)
        self.assertIn("button disabled", html)
        self.assertNotIn("srcdoc", html)
