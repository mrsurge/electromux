# Electron calculator portability proof

## Boundary and intended acceptance

Use Alex313031/electron-calculator at commit
`1bd90d3adb449a94bdff3dec04d01884fe335546` (BSD-3-Clause, version 1.1.4)
as an unrelated consumer. Electromux owns generic compatibility; the sample owns
calculator assets/metadata. Neither requires TE2 or shared UID. Run the original
main/preload/renderer entrypoints where possible; record every required source
edit. Merely displaying calculator HTML is not Electron compatibility proof.

Initial support is one main window. Secondary About/License/Humans/link windows
are deferred, not silently ignored. Native chrome is GNOME/GTK-inspired styling,
not a GTK dependency. Menu generation consumes executed Electron menu templates,
not parsed source code. Keep callbacks in Node; native selections carry IDs.

## Manifest feature

```json
{
  "schemaVersion": 1,
  "windows": "main-only",
  "chrome": {"style": "gnome", "titleBar": true, "menuBar": true},
  "disabledMenuItems": {"3/5": "Additional windows deferred"}
}
```

The menu path above is illustrative, not a verified calculator mapping. Paths
are zero-based nested template indices. Disable secondary-window callbacks by
manifest path: arbitrary callback contents cannot be safely inferred. Reasons
must be shown by native chrome. Unexpected additional-window calls still error.
The strict, immutable manifest grants no filesystem/process/security authority.
Unknown fields fail. Future multi-window/switcher support is a separate gate.

## Implemented foundation

`runtime/src/electron-manifest.ts` validates policy.
`runtime/src/electron-compat.ts` defines main-window ownership and ordered native
create/load/close effects, close cancellation and acknowledged destruction.
Native failures are retained and reported, never successful no-op replies.
Menu DTOs carry presentation state and revision-scoped IDs, never callbacks.
Selections reject stale/hidden/disabled/non-actionable entries. Unsupported
roles and actionless leaves are disabled; callbacks execute in Node.

The initial foundation passed 30 runtime tests. The subsequent checkpoints below
add the module facade, preload and native APK; installed acceptance remains pending.

## Next slice and validation gates

### Native calculator implementation checkpoint (2026-10-08)

`ElectronChannel` is an opt-in driver with the existing bounded JSON framing and
writer: one active command, one correlated native effect, concurrent one-way
ACK reads, deadlines and explicit failure/teardown. Existing ConsumerHost and
INodeHost semantics are unchanged. FramedTransport adds only a serialized
one-way control write, independent of its waiting request lane. The separate
IElectronHost/EmbeddedElectronService authorizes each call by app UID.

The ordinary-UID `android/calculator` target packages embedded Node and Cefrium
0.9.0. It has no TE2 dependency, Termux permissions or compatible-signing demand.
The generic ElectronMenuChrome generates native menus from DTOs and dispatches
IDs. Reload/forceReload/quit are supported roles; unavailable roles remain
disabled. Main-window navigation callbacks dispatch native browser operations.
Application relaunch, detached DevTools, extra windows and third-party context
menus remain explicit limitations.

The builder pins/verifies source, preserves domain app.js/preload.js byte-for-byte,
copies real electron-log 5.1.1, and generates only publication HTML with the
original lexical preload before app scripts plus an exact additional CSP hash.
The exact packaged document sends ready/error through the native Cefrium query
binding. window.load success requires this ready signal, not mere loading-state
completion (which can also describe an error page). Renderer loss rejects a
waiting load. No metadata APIs become window globals. Ordinary Node app code
is trusted application code, not sandboxed.

Build after installing the pinned upstream production dependencies:

```bash
node samples/electron-calculator/build.mjs /absolute/path/to/electron-calculator
cd android
./gradlew -PelectromuxCalculator=true :host:testDebugUnitTest :node-runtime:testDebugUnitTest :calculator:assembleDebug
cd ..
node samples/electron-calculator/verify-apk.mjs android/calculator/build/outputs/apk/debug/calculator-debug.apk
```

Use the existing JDK 25/SDK 37, ELECTROMUX_NODE_SDK and ELECTROMUX_NDK_HOME setup.
APK: `android/calculator/build/outputs/apk/debug/calculator-debug.apk`.
Do not install over TE2: app ID is `dev.mrsurge.electromux.calculator`.
Native implementation/build does not establish live arithmetic/preload/menu
acceptance. Tests include ACK-with-pending-command, negative/stale/duplicate ACK,
deadline/disconnect, one-way native control and resource containment. TE2's 31
consumer regressions and Termux native tests/compilation also pass. See
CALCULATOR_SECURITY.md for the intent/security alignment review.

First installed Razr attempt failed during resource extraction: the generated
inventory declared electron-log's `__specs__` tests, which AAPT omitted from the
APK. Node had not launched; the status field displayed the missing asset path.
The builder excludes that non-runtime test directory. The post-assembly verifier
requires every declared domain resource plus renderer/runtime entrypoints to be
present in the actual APK; the old APK fails this gate. Live acceptance remains
pending after the corrected package is installed.

### Razr renderer follow-up (2026-10-08)

USB device `ZY22K74WF4` received the corrected standalone APK. Resource extraction
and Node/menu startup succeeded, but the renderer remained empty. A temporary
exact-document query probe demonstrated that `fetch()` from the file asset origin
failed. The calculator fetches its locale before constructing the UI.

Generic `PackagedAssetServer` now serves only the consumer's fixed APK asset root
on an ephemeral IPv4 loopback listener, with bounded HTTP headers, queue and
socket deadlines. Navigation remains restricted to that exact origin; readiness
queries require its exact `/index.html` and current browser URL. Browser security
is not disabled. The temporary probe was removed; promise errors remain bounded
device-log diagnostics. Successful startup hides its status row.

The upstream bundle also reports `Service Worker registration failed: nan is not
defined`; this is a non-fatal upstream error, not a fake service-worker success.
Main/preload/app bundle remain unmodified. On the installed final build the
calculator content rendered and native taps of `2 + 3 =` produced `5` (screenshot
evidence in root `.codex-scratch/calculator-arithmetic.png`). Native JVM tests,
assembly and actual APK resource-inventory verification pass. Broader menu,
lifecycle and user acceptance remain separate gates; this is not complete
Electron API compatibility.

### Node entrypoint checkpoint (2026-10-08)

Lifecycle follow-up: the calculator's Node service is bound-only, with same-UID
renderer Binder death and final-unbind cleanup. Subscription failures cannot
escape the Activity callback. A stale-owner rejection before any start command
allows one fresh binding only after the old service Binder dies (5-second bound);
this never replays an application command. Density/font/size configuration changes
retain the Activity. Installed Razr tests cover UI-only SIGKILL, fresh reopen,
rapid Back/reopen, density `356 -> 380 -> 356` (restored user override) and another
reopen with successful arithmetic. No calculator FATAL entries appeared after
10:00 in this test window. User acceptance of the follow-up is pending.

The additive `electron-main.ts` facade now supplies explicit app readiness,
metadata, main-window/menu objects, navigation effects, theme and quit effects.
`electron-loader.ts` scopes CommonJS overrides to one trusted app graph; it does
not monkey-patch global require or spoof the host's Electron version. This is
module loading, not a sandbox: application main code still has Node authority.
The original preload runs in its own lexical scope with metadata-only remote
and process objects; neither becomes a renderer global.

Approved dependency adaptations: electron-log uses its real official Node
entrypoint; @electron/remote is metadata-only (no Electron native v8 bindings);
third-party context menus are explicitly deferred in the consumer manifest.
Chromium switches requested after Android engine initialization are reported as
deferred, not claimed applied. Native roles must be capability-backed; unavailable
roles remain disabled. Container menu roles must not disable supported children.

The pinned upstream app.js and preload.js execute without source changes in the
Node/VM probe, producing create/menu/theme/load effects, executing the actual
navigation callback and rejecting secondary-window menu selections. This does
not prove browser arithmetic, native menus or document-start preload timing.
The consumer manifest lives in `samples/electron-calculator/electromux.json`.

Reproduce using a clean checkout at the pin above, with its actual production
dependencies installed (`npm ci --omit=dev --ignore-scripts` in upstream `app/`):

```bash
cd runtime
npm run typecheck
node probe-calculator.mjs /absolute/path/to/electron-calculator
npm test
npm run build
```

The probe checks the source commit, metadata and unchanged tracked app source
before executing; it does not fetch/install packages or modify the checkout.
The upstream-dependent test is opt-in in the ordinary suite; the probe explicitly
enables it. Preserve this actual-source gate alongside synthetic tests.

TE2 regression boundary: existing ConsumerHost/runRuntime, native Binder methods
and TE2 adapter exports are unchanged. All 31 TE2 consumer regressions pass.
The native checkpoint implements the bounded full-duplex acknowledgement lane:
a JS callback can await a native effect while acknowledgements continue to be
read. Do not route those acknowledgements through the serial ConsumerHost request
loop. The opt-in Electron driver reuses bounded framing without changing the
accepted TE2 consumer. All 40 runtime tests, including the actual-source probe,
pass; installed renderer/menu/lifecycle acceptance is still required.

1. Pin/package calculator source and license. Audit actual electron-log,
   electron-context-menu and @electron/remote APIs before substituting anything.
2. Add real Electron module loading, app readiness/metadata, BrowserWindow,
   Menu and required webContents methods over the existing ownership lane.
3. Wire a separate ordinary-UID Android sample, embedded Node and Cefrium.
   Native title/menu chrome consumes DTOs; selections dispatch actual callbacks
   or implemented roles. No calculator-specific core handlers or inert controls.
4. Preserve preload-before-renderer execution. Immutable bootstrap metadata can
   serve synchronous version lookup without synchronous process round trips.
   No arbitrary remote proxy/global Node exposure. Validate local file routing,
   CSP and navigation; upstream Chromium switches are not blanket permissions.
5. Installed acceptance: arithmetic, menu callbacks/roles, disabled reasons,
   main-window close, lifecycle and diagnostic failures. Record all source edits
   and remaining unsupported APIs. Synthetic tests are not drop-in acceptance.

No TE2 framework/client, signing lane or existing chrome changes. APK assembly
and installation are separately approved. Multi-window behavior remains deferred.
