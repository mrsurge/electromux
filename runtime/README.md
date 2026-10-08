# Embedded Node proof

This opt-in, framework-independent experiment does not replace the accepted
Python helper/external-Node host. It embeds the pinned full ARM64 Node SDK from
`node-sdk.json` into a separate Android service process. No TE2 import or installed
Termux Python/Node is needed by this proof. This is not an Electron API-complete SDK.

## Build

From this directory, with development Node 24+ and npm installed:

```sh
npm ci
npm run typecheck
npm run build
npm test
node prepare-sdk.mjs "$PWD/.codex-scratch/node-sdk"
```

Then from `../android`, with JDK 25, SDK 37 and NDK 28.0.13004108 configured:

```sh
ELECTROMUX_NODE_SDK="/absolute/path/to/verified/node-sdk" \
ELECTROMUX_NDK_HOME="/absolute/path/to/ndk/28.0.13004108" \
./gradlew -PelectromuxEmbeddedNodeProof=true \
  :node-runtime:testDebugUnitTest :node-proof:assembleDebug
```

The proof APK is `node-proof/build/outputs/apk/debug/node-proof-debug.apk`.
The flag leaves the existing sample/host configuration unchanged by default.
Archive SHA-256 is checked before extraction; CMake also checks the imported
ARM64 library hash. Headers require C++20. SDK and build outputs are not source.

## Ownership and tests

Kotlin owns an APK-private Binder API and a dedicated socketpair; JNI supplies
`node::Start` on a background thread. A process-owned slot retains the engine
across started, non-sticky `:node` Service and Activity recreation. Services are
adapters, not engine owners; `onDestroy()` must not close/reinitialize Node.
Startup failure is retained without retry in that process. Binder returns an
explicit error DTO rather than throwing unsupported checked exceptions. Closing the
channel does not promise engine restart or background survival. No `process.exit`,
arbitrary eval/path/command, HTTP control listener, or automatic mutation replay.

Strict TypeScript consumes bounded length-prefixed UTF-8 JSON requests and emits
correlated replies plus unsolicited events. This sample protocol is independent
of TE2's MessagePack worker contract. Native transport reuses the existing host's
FrameCodec/FramedTransport and their JUnit tests, without packaging its Python
helper. Test seams are explicit constructors; no DI framework is introduced.

Host tests cover malformed/truncated/oversized frames, method allowlisting,
filesystem/ping handlers and the actual built bundle over FD3 for 30 successive
requests/events. JVM tests cover existing native framing/correlation/close bounds.
Three process-slot and two Binder-reply tests cover single initialization,
concurrent acquisition, retained failure and explicit error reporting.
Device acceptance on Motorola Razr confirms boot/ping/filesystem/events,
Activity reopen, Service destruction/recreation with the same engine PID, and
explicit proof-package force-stop/reopen with a fresh PID and request sequence.
This does not establish automatic crash recovery or background survival.

## Gates still required

- Broader lifecycle/crash testing; the explicit process-stop/reopen gate passes.
- Shared Termux signature/UID and controlled child execution, environment,
  stdout/stderr, exit, cancellation and extra-FD readiness.
- Cefrium renderer/document authorization, lifecycle and C++ runtime coexistence.
- Inspector and native debugging, crash behavior and resource measurements.
- Archive recipe/source provenance and complete third-party license notices before
  redistribution. The binary archive alone is not a complete license inventory.
- Supervisor/API parity before migrating TE2 or removing the old helper.

The proof uses ordinary debug signing, not the Termux shared-UID distribution lane.
Its native diagnostic buttons intentionally precede browser integration.
