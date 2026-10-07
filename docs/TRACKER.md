# Electromux independent sample tracker

Current integration direction: [Desktop-language POC](DESKTOP_PARITY_POC.md).
Build a working TE2 Termux consumer using portable Desktop code and Android
adapters, then extract/stabilize reusable hosting, branding and custom-route APIs.
The direction document alone grants no implementation/publication approval;
the first bridge slice was separately approved with targeted local validation.

- [x] Inventory Desktop request/events and portable JS/TS reuse candidates.
- [x] Implement native-owned consumer descriptor and guarded request/reply bridge foundation.
- [x] Add disposable browser subscriptions for bounded reply-associated events.
- [ ] Complete build-time branding, stable-origin/custom serving and backend adapter configuration.
- [ ] Prove unsolicited native event delivery and renderer lifecycle fencing.
  Helper transport foundation is locally tested: one reader, correlated replies,
  authenticated event opt-in, 16-frame writer queue, slow-client disconnect and
  detached-event discard. Generic Kotlin stream demultiplexing now compiles and
  has JVM coverage; renderer delivery and service integration remain pending.
- [ ] Remote-only TE2 Termux POC, then owned local/external framework parity.
- [ ] Desktop feature matrix and both-device lifecycle acceptance.
- [ ] Separately approved POC publication and reusable SDK extraction.

- [x] Private independent repo and consumer-neutral layout.
- [x] Framing and real Unix-socket/subprocess sample tests.
- [x] Android/Cefrium build and bundled-page scaffold.
- [ ] Select repository license before public distribution.
- [ ] Add pinned Cefrium source submodule; prove SDK/plugin/resource combination.
- [ ] Verify signed sample APK and installed shared UID on Motorola/Pixel.
- [ ] Implement explicit Termux launch adapter and installer progress interface.
- [x] Add native UID/signature/service inspection and diagnostic launch adapter.
- [x] Compile Android sources and pass 3 launch-contract JVM tests plus 10 Python regressions.
- [x] Verify correlated native diagnostic completion on Pixel (Motorola repeat pending).
- [x] Wire Android filesystem socket client/native bridge to helper (Pixel accepted; Motorola repeat pending).
- [x] Implement fixed trusted-page controls, bundled helper provisioning and bounded native/Python I/O.
- [ ] Implement local-origin asset hosting; no server fetch dependency.
- [ ] Bound socket/backend timeouts, queues and cancellation; concurrent clients.
- [ ] Test activity/renderer recreation, lock/background and reconnect.
- [ ] Test install/upgrade/failure rollback/uninstall preserving user state.
- [x] Complete frontend-to-backend-to-frontend acceptance on Pixel; second-device repeat remains pending.
- [ ] Add TE2 Termux consumer as a separate app; existing clients unchanged.

Checked items indicate scaffold/test scope only, not complete native acceptance.

Next gate: [signed-device diagnostic procedure](DEVICE_LAUNCH_PROCEDURE.md).
Reconnecting devices and explicit build/install approval are prerequisites.

## Pixel diagnostic checkpoint (2026-10-06)

- Pixel Android 17/API 37, arm64; Termux 0.119.0-beta.3, Python 3.14.6.
- Approved signed debug sample assembly/install succeeded. APK is arm64-only,
  approximately 193 MiB, SHA-256
  `c976eaa577c854a1776e6642175447ab9fecc0e7b7df5dcecc3e00b64e314dfd`.
- Installed sample and Termux share actual UID 10321 and certificate SHA-256
  `b6da01480eefd5fbf2cd3771b8d1021ec791304bdd6c4bf41d3faabad48ee5e1`.
  Upstream public test key is kept in ignored local test state, not source.
- Assembly needed the existing TE2 Cefrium exclusion of redundant Guava
  `listenablefuture`: Cefrium already embeds that class. Three JVM tests and
  ten Python regressions pass.
- Native identity status passes; bundled sample page renders; one native
  diagnostic button press reports dispatch without an exception.
- **Completion is not accepted:** bounded logs did not establish command output
  or exit status. Next approval scope should add an explicitly targeted,
  correlated completion observer. Do not repeat uncertain commands or equate
  service dispatch with readiness. No Termux reset or existing TE2 APK change.

## Pixel completion observer acceptance (2026-10-06)

This follow-up supersedes the preceding completion blocker. Updated signed sample
installed successfully with the same shared UID/certificate. APK SHA-256:
`5290e5478143b373086af9f66f61b625a60ae92ff6541e16622412a92ecb9a82`.
One native button dispatch delivered a correlated completion callback displayed
in the UI: stdout `Python 3.14.6`, empty stderr, exitCode `0`, Termux err `-1`.
Termux's pinned Errno.ERRNO_SUCCESS uses Android Activity.RESULT_OK (`-1`), so
that field is not an execution error. Six JVM and ten Python tests pass;
signed debug assembly succeeds. Existing Cefrium resource warnings remain.

A direct ADB broadcast with an unmatched token did not alter the displayed
result; this is not proof against a malicious same-UID app or a comprehensive
Android security test. The final manifest independently confirms a non-exported
receiver. Timeout/late-result behavior is covered by pure state tests, not a
physical timeout injection. Process-death recovery is explicitly unsupported.

Next: separately approve authenticated helper filesystem socket/native page
bridge implementation, with actual hello/ping/event round trip, retained owned
backend reconnect and explicit stop. Repeat signing/launch acceptance on Motorola
when available. Installer, asset hosting and lifecycle stress remain separate.

## Authenticated helper bridge implementation (2026-10-06)

Bundled helper provisioning, fixed trusted-page controls, native filesystem
socket authentication/correlation, bounded queues/deadlines and authenticated
shutdown are implemented. Twelve Python tests, nine JVM tests, the actual
bundled-page JavaScript regression and signed debug assembly pass.

The first Pixel page Connect attempt reported native rejection before any
helper subtree/socket was created. A follow-up APK exposes the rejection code
and bounded message without exposing credentials; it installed successfully.
Device became locked before follow-up inspection. Follow-up APK SHA-256:
`6cef9dced9f80dc59d54aa20dd41f833e35130236e76a3ca06c272fa7f147f44`.
Native round-trip acceptance is **pending**, not inferred from local tests. Preserve the exact trusted-page
guard while investigating. No shared framework restart, Termux reset or existing
TE2 client modification was performed.

Follow-up investigation identified `-1: The query has been canceled` from CEF's
unhandled-query path, not the page policy. Cefrium 0.9.0 `setQueryHandler` does
not register its native Java browser target; the public loading-state listener
does through `nativeSetDisplayCallback`. The sample now registers that listener
before loading. Thirteen Python tests (including source wiring regression),
nine JVM tests and assembly pass. Corrected APK installed on Pixel, SHA-256
`9f4c9ae232350900b448d16542e6a92d303030e8446df2ff8ca0589320696bce`.
Physical retest awaits the device unlock after transfer; no policy relaxation.

## Pixel helper bridge acceptance (2026-10-06)

The preceding retest gate is closed for the corrected APK identified above.
Physical UI actions completed Connect, Start and Ping, displaying the correlated
`pong: hello from bundled page` and `sample.updated` response event. Detach then
Connect retained backend PID 24758 under helper PID 24724. Private session
directory mode was 0700; token and filesystem socket were 0600. Stop returned
stopped/null PID and removed the backend; authenticated Shutdown subsequently
removed the helper and socket. Final process listing showed neither sample
process. Credentials were not read into diagnostics. No existing TE2 runtime,
APK or Termux user state was reset.

One initial Shutdown attempt encountered the configured 30-second idle
connection expiry and failed without mutation replay. Explicit Connect then
Shutdown succeeded. Future lifecycle polish should make expired client state
clear in the page without polling or uncertain retries. This is not acceptance
of Activity/renderer recreation, concurrency, unsolicited streaming, installer
rollback or the Motorola. Thirteen Python tests, nine JVM tests and the bundled
JS regression pass. The native registration workaround is in CONTRACT.md.

Android `run-as`/astermux `kill -0` returned EPERM for the live helper despite
its shared UID. Do not interpret that as process exit: verify with exact process
list, authenticated status and socket cleanup. The final listing/removed socket,
not that failed signal probe, establish shutdown here.

## Consumer bridge foundation (2026-10-06)

Source inventory and boundaries: [Desktop API inventory](DESKTOP_API_INVENTORY.md).
Native-owned descriptors/handler registration and transport-injected browser
request/reply/disposable subscriptions now drive the independent sample. The
helper wire/authentication remains unchanged; reply-associated events are
implemented, unsolicited streaming is not. Android compilation and 11 JVM tests,
13 Python tests and browser deadline/correlation/queue/disposal regressions pass.
No APK assembly, device modifications, TE2 runtime changes, commit or publication
was performed in this slice. Installed Pixel sample remains the earlier bridge
build; its acceptance must not be attributed to these new source changes.

## Generic native reply/event transport (2026-10-06)

- [x] Move FrameCodec into reusable host package.
- [x] Add stream-injected FramedTransport with one reader and serialized requests.
- [x] Separate declared events from correlated replies; bounded 16-callback queue.
- [x] Idle-safe read, partial-frame/request deadlines, fail-closed disposal, no replay.
- [x] Adapt HelperClient after authenticated hello; sample remains request-only.
- [x] Seven JVM transport regressions plus existing sample tests; Kotlin compilation.
- [ ] Renderer/page event subscription and lifecycle fences.
- [ ] Generic consumer provisioning and TE2 persistent-service/relay integration.
- [ ] New APK and physical acceptance (not approved in this source-only slice).

No manifest, signing, Termux launch authority, device state or shared framework
changes. Native configuration alone supplies event names/callbacks. Connection
disposal does not stop the retained helper/backend. The existing installed Pixel
sample has not been updated. This slice is uncommitted after checkpoint fdfb3f1.

## Document-fenced renderer events

- [x] Optional browser document ID and generic receiveEvent with declared listeners/disposal.
- [x] Native RendererEventGate checks exact current page and document/generation binding.
- [x] Sample native event opt-in and quoted-JSON UI delivery (maximum 16 pending posts).
- [x] Loading/URL callbacks invalidate stale requests/replies/events; disconnect never stops backend.
- [x] Independent sample.state helper frame; reply-associated sample.updated retained.
- [x] 22 JVM tests (four renderer-lifetime regressions), 21 Python tests, browser
  wrong-document/malformed/disposal regressions and Android Kotlin compilation.
- [ ] APK/physical verification of callback ordering, reload and unsolicited delivery.
- [ ] Generic provisioning and persistent-service integration for TE2 consumer.

Source-only implementation; no APK/device/shared-framework changes. Existing
installed sample and TE2 Termux clients remain unchanged. No Electron compatibility
claim follows from this sample. All changes since fdfb3f1 remain uncommitted.
