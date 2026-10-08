# Optional Chrome resource/platform integrations absent from the Cefrium SDK.
# These suppress missing optional references; they do not retain whole packages.
-dontwarn org.chromium.chrome.**.R$*
-dontwarn org.chromium.components.**.R$*
-dontwarn org.chromium.webapk.**.R$*
-dontwarn org.chromium.third_party.**.R$*
-dontwarn org.chromium.ui.**.R$*
-dontwarn com.airbnb.lottie.R$*
-dontwarn com.airbnb.lottie.**.R$*
-dontwarn com.google.android.gms.**.R$*
-dontwarn com.google.android.material.R$*
-dontwarn com.google.android.material.**.R$*
-dontwarn com.google.ar.core.R$*
-dontwarn com.google.ar.core.**.R$*
-dontwarn android.app.HandoffActivityData**
-dontwarn android.app.HandoffActivityParams**
-dontwarn android.webkit.WebViewDelegate
-dontwarn kotlinx.coroutines.guava.ListenableFutureKt
-dontwarn org.chromium.chrome.browser.ProductConfig
