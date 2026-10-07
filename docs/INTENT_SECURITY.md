# Best Practices and Security Alignment Update

## Reusable library execution boundary (2026-10-07)

Priority: high. Scope: android/host, sample TermuxLaunchAdapter/SampleConsumer/
SampleRuntimeService/MainActivity and Gradle module/asset configuration. No
new Android permissions, components or external runtime dependencies are added.
Existing UID/signature/service checks and explicit dispatch move intact to
TermuxHelperLauncher. The sample retains its native diagnostic PendingIntent
creation; the host accepts that already-configured optional completion only from
native callers. Generic backend requests are consumer-validated DTOs, not argv.

```diff
- sample owns HelperClient and launch verification
+ host owns TermuxHelperClient and TermuxHelperLauncher
+ sample implementation(project(":host"))
- client.call("ping", value)
+ client.requestBackend(nativeConsumerPingPayload)
```

Library manifest is empty: it cannot silently grant service/launch identity to
consumers. Host tests are independent of SampleConsumer; sample page-policy and
diagnostic tests remain consumer-owned. AAR/merged-asset inspection and sample
compilation verify composition, not installed signing/UID/background eligibility.

## Private runtime service and native provisioning (2026-10-06)

Priority: high; avoid exported execution authority and renderer-owned process
lifetime. Scope: SampleRuntimeService, MainActivity, SamplePageBridge,
HelperInstallSpec/HelperProvisioner/RuntimeOwner, HelperClient and manifest.

The service is explicitly targeted, non-exported and non-sticky; incoming Intent
extras are not commands. Its local Binder verifies the calling UID. Shared UID
and public signing keys still provide compatibility, not malicious-peer isolation.
Native consumer configuration alone supplies roots/assets/backend executable and
environment. Traversal and managed symlink destinations are rejected; private
files have bounded reads and temporary-file publication. Existing Termux installed
UID/signature/service verification is unchanged. No AndroidX dependency is added.

```diff
+ <service android:name=".SampleRuntimeService" android:exported="false" />
+ check(Binder.getCallingUid() == Process.myUid())
+ override fun onStartCommand(...) = START_NOT_STICKY
+ unbindService(connection) // page teardown, not backend shutdown
```

Tests cover immutable path/config guards, bounded queues/subscriptions, local
observer failure and detach/rebind ownership; a manifest/source regression checks
the private service, callback-registration ordering and absence of teardown
Shutdown. Kotlin compilation passes. These are not physical lifecycle/background
acceptance or a claim of security against intentionally shared-UID applications.

## Explicit Termux launch and installed identity verification

Priority: high. This prototype crosses into Termux's non-exported execution
service; unintended command dispatch or intent redirection would expose its
execution environment.

Scope: `LaunchContract.kt`, `TermuxLaunchAdapter.kt`, `MainActivity.kt`,
`LaunchContractTest.kt` and sample Gradle test/signing configuration.

Implementation:

- Native consumer configuration constructs the executable/argv/cwd spec;
  no web message or incoming Activity Intent is deserialized into it.
- Explicit component/action/data scheme, fixed background runner, no arbitrary
  nested Intent forwarding and no URI permission grant flags.
- Each dispatch checks that Termux is installed, has matching signing
  certificates and actual UID, and its service is enabled.
- The only visible sample execution action dispatches `python --version`.
  UI reports dispatch, not command completion or backend readiness.
- `onCreate` constructs native controls and browser state; incoming launch
  extras are ignored. No singleTop/onNewIntent command path is introduced.
  There is no PendingIntent or exported result receiver in this slice.
- Public GitHub signing keys/shared UID do not establish isolation against
  malicious apps deliberately joining that trust domain.

Key implementation diff:

```diff
+ check(inspect().canLaunch) { "Termux identity/service verification failed" }
+ context.startForegroundService(executionIntent(spec))
```

The explicit intent contains only the configured argv/cwd and Tasker-compatible
runner fields. No executable comes from a page, Intent extra or deep link.

Validation: pure JVM tests cover configured argument preservation, forbidden
paths/NUL rejection and all required identity facts. Python regressions cover
the independent helper/protocol. Android compilation checks platform API use.
These do not establish actual service-start eligibility, installed UID, command
completion or modern Android foreground restrictions; device acceptance remains
mandatory. Full bridge/installer and bounded progress are subsequent work.

Checkpoint validation (2026-10-06): Android `compileDebugKotlin` and
`testDebugUnitTest` succeeded (3 tests, no failures/errors); the independent
Python suite passed all 10 tests. No APK was assembled or installed. Cefrium
resource/default-value warnings remain build diagnostics, not device evidence.

## Correlated diagnostic completion (2026-10-06)

Priority: high; prevent callback redirection, duplicate/late result adoption and
false success from missing exit fields. Scope: DiagnosticCompletion.kt,
DiagnosticState.kt, DiagnosticStateTest.kt, TermuxLaunchAdapter.kt,
MainActivity.kt and AndroidManifest.xml. No dependency added.

The result receiver is non-exported, explicitly targeted by a one-shot
PendingIntent. Mutability is necessary because Termux fills its result Bundle;
action/component/data are preconfigured, and the receiver never forwards an
Intent or executes anything. A process-local random request token matches only
the one pending diagnostic. Completion, dispatch failure and a 30-second timeout
cancel the callback. Late/duplicate/unmatched results are ignored. UI retains
only bounded output (4096 characters per stream); missing or mistyped exit/error
fields remain missing, never default to success. Activity teardown detaches its
observer; process-death recovery is not provided. No automatic command retry.
Shared-UID/public-key trust limitations still apply.

```diff
+ <receiver android:name=".DiagnosticResultReceiver" android:exported="false" />
+ val intent = Intent(context, DiagnosticResultReceiver::class.java)
+ val flags = PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_MUTABLE
+ if (!DiagnosticCompletion.accepts(id)) return
+ context.startForegroundService(executionIntent(spec).putExtra("pendingIntent", completion))
```

Pure JVM tests cover one-shot correlation, duplicate/late results, single-flight
ownership, timeout-state retirement, bounded output and missing-exit reporting.
Physical callback delivery/output/exit acceptance remains separately recorded
in TRACKER.md; unit tests do not prove Android receiver/PendingIntent delivery.

## Authenticated bundled-page helper bridge

Priority: high. Scope: SamplePageBridge, HelperClient, SampleProvisioner,
FrameCodec, MainActivity, bundled page and helper. Only the exact bundled
`file:///android_asset/index.html` URL can invoke seven fixed methods. The page
cannot provide executable paths, shell strings, socket paths or credentials.
JSON input has an explicit field allowlist and size limits; responses use
textContent, not HTML. CSP excludes frames, external scripts and form actions.

Native provisioning copies four bundled Python files into a content-addressed,
sample-owned Termux cache subtree. Private session directories/token/socket use
0700/0600 permissions. The random token remains native-side. Every launch still
checks installed UID/signature/service; no public RUN_COMMAND entrypoint exists.
An uncertain launch is never automatically repeated. The public signing key and
shared UID remain a cooperative trust boundary, not isolation from malicious
same-UID processes.

```diff
+ require(origin == BridgePolicy.PAGE)
+ require(method in BridgePolicy.methods)
+ connection.connect(LocalSocketAddress(path, LocalSocketAddress.Namespace.FILESYSTEM))
+ exchange(helloWithNativeSessionToken)
+ timer.schedule({ connection.close() }, 5, TimeUnit.SECONDS)
```

Socket frames are capped at 64 KiB with strict UTF-8 and request correlation.
One native I/O executor has a bounded eight-item queue; connection/request
deadlines close the exact socket, without replay. Python backend pipe operations
also have deadlines. Detach and Activity teardown close the client but retain
the owned backend; explicit authenticated shutdown reaps it and removes the
socket. Correlated ping events are response data, not unsolicited streaming.
Concurrent client service, process-death recovery and renderer lifecycle stress
are not established by this slice. Validation evidence belongs in TRACKER.md.
