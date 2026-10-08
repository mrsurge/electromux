# Electromux

## Embedded Node / Electron-shaped proof of concept

The current development branch adds embedded Node 24 and a typed, additive
Electron-shaped main-process facade. The independent
[calculator proof](docs/ELECTRON_CALCULATOR_POC.md) executes the pinned Electron
Calculator main/preload without source edits, with native menu callbacks and
packaged loopback renderer assets. Installed Razr arithmetic (`2 + 3 = 5`) is
verified. This is a useful-subset POC, not complete Electron compatibility.

The initial public checkpoint exposed a retained renderer subscription on UI
relaunch. The lifecycle follow-up uses bound ownership, Binder-death/final-unbind
cleanup and a single death-gated handoff before application startup. Razr UI-death,
rapid reopen and density-change recovery tests pass. This remains a POC, not a
production SDK; user acceptance and broader lifecycle coverage are separate.

The older helper scaffold below remains for historical/Termux-specific tests;
it is not the embedded JavaScript runtime used by the calculator or TE2 Termux.

Experimental framework-agnostic Android application host: Cefrium renders a
consumer's bundled frontend; a Termux helper connects it to an owned backend.
This is an initial scaffold, not a shipping SDK or a replacement for Cefrium.

TE2's future consumer is **TE2 Termux**, a separate APK alongside TE2 Cefrium
and TE2 Gecko. This sample has its own application ID `dev.mrsurge.electromux.sample`.
No TE2 imports or application-specific services belong in the host core.

## Implemented in this checkpoint

- Bounded length-prefixed control frames using standard-library Python.
- Local Unix-socket helper with session/token handshake and a single owned
  sample backend, explicit status/start/request/stop/detach and reconnect.
- Executable independent backend and real subprocess/socket regression tests.
- Android/Cefrium Gradle and activity scaffold with a bundled sample page.

The Android sample has native identity inspection and an explicit diagnostic
Termux launch adapter (`python --version`) and a restricted bundled-page helper
bridge. Native code provisions the bundled Python helper into a sample-owned
Termux cache directory, retains credentials and performs bounded authenticated
filesystem-socket requests. A correlated native completion observer displays
bounded stdout/stderr, exit status and Termux errors, with a 30-second timeout
and no automatic retry. Signing/shared UID,
renderer lifecycle and the complete round trip have separately recorded
acceptance gates. See [intent security notes](docs/INTENT_SECURITY.md) and the tracker.

## API-declared chrome surfaces

Consumers declare a packaged chrome document with `ChromeSurfaceSpec`, its
`ConsumerDescriptor`, placement and a bounded height. Calling `attach(host)`
delegates rendering and returns a `Closeable`; `ChromeSurfaceHost` is the
renderer-specific adapter. The core contract imports neither Cefrium nor any
consumer application. Consumers own HTML/CSS, button listeners and action
semantics; adapters own native attachment and lifecycle.

Use the existing `ElectromuxBridge.create` query protocol for the chrome
document. A consumer can expose an Electron-preload-shaped `request` and
subscription facade without authorizing arbitrary IPC channels. This is not
full BrowserWindow/Electron compatibility. Authorize the exact browser and
packaged document, fence navigation/document generations, bound pending work,
and dispose the renderer independently from backend ownership.

## Run the host-independent tests

```sh
python3 -m unittest discover -s tests -v
```

Tests create isolated temporary directories and processes; no shared runtime
or installed Termux state is used. They work on ordinary Linux and should be
repeated inside Termux before device acceptance.

## Android scaffold

The internal `android/host` Android library contains the reusable native
descriptor, page protocol, framing/event gates, runtime owner, provisioning and
Termux helper client/launcher. It has no Cefrium or TE2 dependency and declares
no application, service or signing identity. Its AAR carries the generic browser
bridge and helper Python assets; consumers supply their backend declaration,
branding, Activity/browser and service owner. `android/sample` consumes this
module rather than compiling another copy of it. This is an internal integration
boundary, not a stable/public Electron-compatible SDK or Maven publication.

Build the library and validate the sample without creating an APK:

```sh
cd android
./gradlew :host:testDebugUnitTest :host:assembleDebug :sample:testDebugUnitTest :sample:compileDebugKotlin :sample:mergeDebugAssets
```

The library output is `android/host/build/outputs/aar/host-debug.aar`; do not
commit that generated artifact or use an unversioned developer path as a shipping
dependency. A pinned source/artifact consumption lane for TE2 remains the next gate.

From `android/`, use the pinned Gradle wrapper with JDK 25 and SDK 37. No
signing key is committed. Set `ELECTROMUX_KEYSTORE`, `ELECTROMUX_STORE_PASSWORD`,
`ELECTROMUX_KEY_ALIAS`, and `ELECTROMUX_KEY_PASSWORD` to a deliberately selected
GitHub-Termux-compatible key for device builds. The build rejects APK tasks
without explicit signing configuration; ordinary auto-generated debug keys
cannot establish the shared UID contract.

The manifest declares `com.termux` shared UID on the root. Actual installed
UID/signature and modern Android behavior must still be proven; do not install
over another client's application ID. Never uninstall Termux to accommodate
a test APK without explicit user approval and backup.

## Architecture and next steps

See [CONTRACT.md](docs/CONTRACT.md) and [TRACKER.md](docs/TRACKER.md).
The sample control codec is JSON; consumer payload transport is not yet a
public contract. TE2 integration must preserve existing MessagePack boundaries.
No generated HTML/srcdoc delivery is planned. Existing assets load locally;
backend events update the running page instead of replacing its document.

The Cefrium reference pin is in `vendor/cefrium-source.json`. A nested source
submodule and published Gradle/Maven libraries are later integration work, not
implemented by that metadata file.

Repository licensing must be selected before public distribution; this private
scaffold makes no license grant. Gradle wrapper files retain upstream notices.
