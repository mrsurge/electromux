# Best Practices and Security Alignment Update: Calculator consumer

## Alignment and priority

Medium priority: keep embedded Node privileged execution private to this ordinary
application UID, separate from TE2 and Termux shared-UID execution. The exported
launcher consumes no execution targets, entrypoints, nested Intents or extras.
It uses singleTask to avoid a second UI owner; no onNewIntent payload is consumed.
Application onCreate builds its fixed UI and explicitly binds its private service.

## Modified scope and implementation

- `android/calculator/src/main/AndroidManifest.xml`: exported launcher only,
  non-exported dedicated `:electron_node` service, no shared UID, backup disabled.
- `android/node-runtime/.../EmbeddedElectronService.kt` and `IElectronHost.aidl`:
  fixed APK-owned consumer; same-UID authorization on every Binder operation;
  separate ACK and command lanes; no page-selected entrypoint or service target.
- `EmbeddedConsumerSpec.kt` / `EmbeddedNodeRuntime.kt`: immutable bounded resource
  declaration, relative-path validation/canonical containment, 64 MiB copy limit.
- `android/calculator/.../MainActivity.kt`: fixed packaged document, restricted
  request URLs, no renderer Node integration; metadata-only packaged preload.
  Exact document URL gates ready/error signals, not arbitrary native commands.
- `PackagedAssetServer.kt`: fixed APK root, IPv4 loopback only, GET/HEAD only,
  traversal rejection, bounded headers/worker queue and socket deadlines. The
  calculator permits cleartext for this local origin; its interceptor rejects
  other network origins. No filesystem API or execution route is exposed.
- `samples/electron-calculator/build.mjs`: pin/unchanged-source checks, generated
  preload insertion with exact CSP hash, no unsafe-inline or web-security disable.

Existing INodeHost/TE2 methods are unchanged. New files do not broaden their
caller policy. This is an application host, not a sandbox for untrusted app main
scripts: approved Node main code has normal Node filesystem/process authority.

## Key unified diffs

New manifest declarations (no implicit service routing):

```diff
+<application android:allowBackup="false" ...>
+  <activity android:name=".MainActivity" android:exported="true" android:launchMode="singleTask" ...>
+  <service android:name=".CalculatorService" android:exported="false" android:process=":electron_node" />
+</application>
```

New service transaction guard (called by subscribe/request/acknowledge/close):

```diff
+private fun authorize() { check(Binder.getCallingUid() == Process.myUid()) { "Private Electron caller rejected" } }
```

Resource path/size guard:

```diff
+check(destination.canonicalPath.startsWith(root.canonicalPath + File.separator))
+resourceBytes += count; check(resourceBytes <= 64L * 1024 * 1024) { "Resource byte limit" }
```

## Verification and remaining acceptance

Strict TS, real unchanged-upstream probe, native one-way ACK regression, immutable
resource/path tests and native compilation pass. TE2 consumer regressions and
TE2 Termux JVM/compile comparison pass. No APK has been installed for this slice.
Physical preload/asset routing, menu actions, arithmetic, close/reopen and renderer
loss remain live gates. Do not treat a successful package build as installed proof.
