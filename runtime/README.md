# Embedded Node proof

This opt-in, framework-independent experiment does not replace the accepted
Python helper/external-Node host. It embeds the pinned full ARM64 Node SDK from
`node-sdk.json` into a separate Android service process. No TE2 import or installed
Termux Python/Node is needed by this proof. This is not an Electron API-complete SDK.

## Build

Reusable private-FD hosting lives in src/runtime-host.ts; an application's
compiled entry supplies its consumer factory. The proof main.ts owns sample
policy. Generic requests carry optional object params, copied/validated with
byte/depth/node bounds. Dispatch permission belongs to the consumer allowlist,
not a hard-coded proof-method parser. Android's EmbeddedConsumerSpec chooses an
exact APK asset entry and immutable method/event declarations; page requests
cannot select files/modules. Process-owned engines must retain their original
consumer selection for their lifetime. TE2's embedded entry is a separate
consumer build; active Android TE2 migration is not yet implemented.

The proof now uses a reusable `ConsumerHost` with retained initialization,
declared methods, nonqueued dispatch and idempotent disposal. Its compiled
factory selects standalone/Termux policy from native startup, not renderer input.
`OwnedChildSupervisor` aborts and joins one owned operation on channel loss.
Activity detach retains the process-owned engine. The diagnostic wire schema,
fixed child commands and readiness token remain sample policy, not public SDK
contracts. `OwnedService` now proves long-lived readiness/status/stop and
unsolicited exit events. Readiness/stop limits are native consumer-selected
(defaults: 3s/250ms; the Termux sample selects indefinite cancellable readiness
and 1s stop grace). Raw output is streamed through OutputPump, bounded to 16
queued chunks of at most 64KiB, 5s delivery deadlines and 2KiB tails per lane;
there is no lifetime-output cap. Consumer events are declared and closed-owner fenced.
`FrameWriter` serializes at most 16 pending frames with a 5s write deadline;
overflow terminates the channel, with no replay. TE2 actor migration and
consumer-specific production protocol/streaming integration are still pending.

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

## Separate Termux execution proof

The opt-in `:node-termux-proof` application reuses the diagnostic source, keeping
`dev.mrsurge.electromux.nodeproof.termux` separate from the ordinary proof and TE2.
Its root manifest joins `com.termux`; assembly requires explicit matching signing
environment, and native startup independently checks the installed UID/signature.
Do not change the ordinary proof's UID or uninstall Termux to resolve a mismatch.

From `../android`, with the same SDK/NDK configuration and the four
`ELECTROMUX_KEYSTORE`, `ELECTROMUX_STORE_PASSWORD`, `ELECTROMUX_KEY_ALIAS`,
`ELECTROMUX_KEY_PASSWORD` environment values supplied outside source:

```sh
./gradlew -PelectromuxEmbeddedNodeProof=true -PelectromuxTermuxProof=true \
  :node-runtime:testDebugUnitTest :node-proof:testDebugUnitTest \
  :node-termux-proof:testDebugUnitTest :node-termux-proof:assembleDebug
```

The Termux-only buttons enable fixed `child.proof`/`child.cancelProof` methods,
authorized through a native startup declaration. They do not accept paths, argv
or environment from a page. Embedded Node directly spawns absolute Termux Bash,
with explicit HOME/PREFIX/TMPDIR/PATH/TERM and Termux exec preload, separate
stdout/stderr plus an inherited FD3 readiness pipe. No external Node, Python
helper, TermuxService broker, framework launch or user configuration mutation.
Output is capped at 8 KiB per lane, operation deadline is 3 seconds, process
groups are owned and cancellation is readiness-triggered. Errors do not retry.

Razr Android API 36 acceptance: matching public GitHub-Termux signer, shared UID
10517, target 34 and observed `untrusted_app_27` domain. Child UID/environment,
deliberate exit 7, separate stderr, exact child PID/FD3 readiness and SIGTERM
group cancellation all pass. Both shell and sleep descendant were absent afterward.
This does not promise other Android/signing-family compatibility or a full
Electron child_process API. The existing TermuxService adapter is not silently
used as a fallback. Native input/output framing remains independent from TE2.
