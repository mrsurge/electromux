# Prototype contract

Status: internal sample contract, version 1; not a stable SDK API.

Native descriptors may also declare one exact `http://127.0.0.1:<port>` origin
supplied by their local relay owner. Every authorized document must still match
an explicit asset route byte-for-byte; queries/fragments, other ports, remote
pages and localhost aliases are not authorized. Serving a page under that origin
does not itself grant a method. The default file-asset policy remains unchanged.

## Internal Android library boundary

`android/host` builds a renderer-independent Android AAR. Native host classes,
Termux identity/explicit launch, authenticated client transport and generic
browser/helper assets live there. It imports neither the sample nor TE2/Cefrium,
and registers no Android service/activity, signing/shared UID or execution
permission automatically. Consumers own those declarations and lifecycle policy.
The sample depends on `project(":host")`; it retains SampleConsumer, ping DTOs,
diagnostic PendingIntent/result handling, Cefrium page adapter and its private
service. The host's `requestBackend(JSONObject)` forwards consumer-validated
payloads with host-assigned correlation IDs. Fixed helper lifecycle methods are
separate; there is no generic page-to-command dispatch.

The AAR includes `electromux-bridge.js` and three generic Python package assets;
the sample supplies `sample_backend.py` separately. Other consumers must declare
their bundled backend through HelperInstallSpec/BundledBackendSpec instead of
relying on the helper CLI's sample default. This is source/build separation, not
SDK stabilization, Maven publication or physical consumer compatibility proof.
TE2 must consume a reproducible pinned source/artifact, never a sibling checkout
path or copied sample classes. Its existing persistent service remains its owner.

## Session and ownership

One helper owns at most one backend. A configured session identifier and random
token bind client requests to that helper. Hello must be first on every new
socket connection. Token/UID checks cannot isolate hostile processes sharing
the same UID/private files; this is a compatibility trust domain, not a sandbox.
No token logging or page URL transport is permitted.

Helper listens on an explicitly supplied filesystem AF_UNIX path inside a
0700 directory. Socket permissions are 0600. Existing socket paths are rejected,
not blindly removed. Disconnect/detach keeps the backend alive. Explicit stop
stops only the helper-owned subprocess; helper exit also reaps its child.
The initial server serializes clients; concurrent presentation support and
cancellation are pending, not simulated by broadcasting responses.

## Control framing

4-byte unsigned big-endian byte length, followed by a UTF-8 JSON object.
Limit: 64 KiB. EOF before a header is a disconnect; partial header/body,
invalid UTF-8/JSON, nonobject body and oversize input are protocol failures.
All operations carry an integer request id and return the same id with result
or error. First request is hello with version, session and token. Methods are
allowlisted: status, start, request, stop, detach, shutdown. No shell strings or arbitrary
executables arrive from the page. Backend argv is selected by helper startup.

The backend's hello is semantic readiness. Its stdout is framed protocol;
stderr is diagnostics, inherited by the helper. Requests use the existing
backend process, never automatic mutation retries. The sample backend supports
only ping and emits a correlated event with the response, not an unsolicited
event stream. Backend frame I/O has five-second deadlines; idle client reads
expire after 30 seconds without stopping the retained backend. Long-running
application streaming and concurrent clients remain separate work.

The helper now optionally relays unsolicited backend events: an authenticated
hello opts in with `events: true`. Events have a nonempty name (at most 128
characters), no request ID, and the same 64 KiB frame limit. One backend output
reader demultiplexes events and integer-correlated replies. Idle backend output
is allowed; partial frames and reply waits retain five-second deadlines.
One writer per connection serializes frames with a bounded 16-frame queue;
overflow disconnects the slow client without stopping the backend. No detached
event history is retained or replayed. A reconnect reads current application
state explicitly. Existing request-only clients need not opt in and receive no
unsolicited frames. Android's sample remains request-only by default. Its
HelperClient now delegates post-handshake I/O to generic `host.FramedTransport`:
one reader, serialized requests, strict correlation, declared event names,
16 queued event callbacks and connection-scoped disposal. Idle is permitted;
started frames and requests have five-second deadlines. Saturation or malformed/
undeclared frames disconnect without replay. FrameCodec lives in the generic
host package. Only native-configured nonempty event names enable hello opt-in.
Renderer delivery and private sample-service ownership now have source
implementations described below; physical acceptance remains a separate gate. Do not
attribute source tests to the previously installed sample.

## Document-fenced unsolicited renderer events

The generic browser bridge accepts an optional per-document `documentId`
(16-80 URL-safe characters). It adds that value to requests and exposes
`receiveEvent(raw)` alongside request/on/dispose. Unsolicited envelopes contain
`documentId`, declared `name`, and object `payload`; wrong-document, undeclared,
malformed, oversized or disposed deliveries are rejected. This ID is a lifetime
fence, not authentication or a command credential. Native exact-page/method
checks remain mandatory; documents with no explicit binding receive no async events.

SamplePageBridge opts into declared helper events. Generic RendererEventGate
requires the native-observed exact current URL and explicit document binding.
Loading/navigation invalidates old tickets; IO-queued requests and UI-posted
replies/events revalidate their generation. Navigation removes the exact renderer
subscription, without disconnecting the service-owned transport/backend.
An already executing mutation is not undone or replayed. Reload starts with a
fresh browser ID and explicit connection/state retrieval, never event replay.

The sample wraps framed `data` in a JSON envelope and calls the installed
receiver through quoted JSON text, not executable payload interpolation. A
maximum of 16 event posts may await the Android UI thread; overflow removes
only the offending renderer subscription. Close invalidates tickets before disposal.
The sample backend preserves its reply-associated sample.updated notification
and emits independent sample.state frames after ping. The Activity wires native
loading/URL callbacks and teardown; the private started/bound sample service owns
the client and protocol independently of Activity/page lifetime.

## Native provisioning and sample runtime lifetime

`HelperInstallSpec` is immutable native consumer configuration: private Termux
home root, preference namespace and target-to-APK-asset map. `HelperProvisioner`
materializes content-addressed packages (64 files, 8 MiB each, 32 MiB total),
rejects traversal/symlink destinations, compares existing content, and publishes
new private files through temporary-file rename. Existing session ID/token and
uncertain-launch guard remain separate from package content. An optional
`BundledBackendSpec` declares the exact Termux executable, bundled entrypoint,
arguments/environment and stop deadline; it becomes an immutable private helper
configuration, never page-provided argv. Provisioning is not an installer/rollback
system and never replaces or stops a live helper. After a seed/declaration change,
explicitly Shutdown the retained helper before expecting the new seed to execute.

`RuntimeOwner` is host-neutral: one eight-item serial request queue and at most
16 disposable observers. Observer failure removes that observer only. The sample's
non-exported `SampleRuntimeService` owns this runtime, HelperClient and protocol;
explicit native start/bind ignores incoming command extras, and the local Binder
checks UID. MainActivity registers Cefrium callbacks before binding/loading the
page. Activity destruction closes its bridge and unbinds, but does not stop the
service or backend. Late Activity callbacks and stale document events are fenced.

The service is `START_NOT_STICKY`, not a foreground/background-survival guarantee.
Android can destroy it; destruction closes client resources without issuing Stop
or Shutdown to Termux. A later explicit Connect reattaches to the retained helper,
subject to existing socket/identity/uncertain-launch guards. No automatic launch
retry, event replay, state polling or credential sharing with browser JavaScript.
Service teardown and actual Activity/renderer recreation require device acceptance.
TE2 persistent-service/relay integration is not implemented by this sample slice.

The Android page bridge accepts only the exact bundled index URL and fixed
connect/start/ping/status/detach/stop/shutdown operations. Native code owns all
registration through `SampleConsumer` and the reusable `ConsumerDescriptor` /
`ConsumerPageProtocol`; browser requests now use `{id,method,params}` and the
Desktop-compatible ok/value or ok/error result envelope. Reply events have
declared names and disposable browser listeners. These are not unsolicited
native event streams; see `DESKTOP_API_INVENTORY.md` for limits and gaps.
Native code continues to own all
paths, credentials and launch specs. Its serialized queue holds at most eight
items and closes the exact socket after a five-second operation deadline.
Initial helper launch waits at most eight seconds for its socket; uncertain
launches are not repeated. Explicit shutdown stops the owned backend/helper;
detach or Activity teardown leaves them available for reconnect.

Cefrium 0.9.0 Surface mode needs native callback registration before loading
the page: `setQueryHandler()` only stores a Java field. The sample also installs
the public loading-state listener, whose `nativeSetDisplayCallback` path wires
the native bridge's Java target. Without it, CEF rejects an unhandled query with
`-1: The query has been canceled` before helper provisioning. Preserve that
registration when changing browser lifecycle setup; do not weaken page policy
to compensate for it.

## Android execution gate

Primary candidate: Tasker-style explicit TermuxService ACTION_SERVICE_EXECUTE
with a background app-shell runner. TermuxService is non-exported; validate
actual shared UID/certificates and foreground-service eligibility first.
Public RUN_COMMAND is a separate optional adapter, not silent fallback.
Completion PendingIntent is not readiness or a streaming transport.

The native diagnostic uses a non-exported, explicit, one-shot mutable broadcast
PendingIntent so Termux can fill its result Bundle. A random process-local request
token binds one outstanding execution; unmatched/late/duplicate callbacks cannot
complete another request. A 30-second timeout cancels the callback without
retrying or killing an uncertain command. Output displayed/retained is bounded
to 4096 characters per field. Activity recreation retains process-local state;
process-death completion recovery is not implemented. This verifies only the
fixed `python --version` diagnostic, not helper/backend readiness.

Consumer installation is separate from launching: explicit consent, verified
artifacts, build/install progress, atomic activation and rollback. Electromux
does not embed TE2/pip/apt assumptions. A source build may outlast readiness
deadlines; do not time its compilation as a failed running backend.

## Reference pins

- Tasker: dbf685fe2973c3490a27cbd37f31909ad5eb3bb3.
- Termux: 8629e632fcb95da272221be327db653fb24befe9.
- TE2: 7d051e4c95e0e75ddb6ea6238dd93bcdcb671953.
- [Tasker execution source](https://github.com/termux/termux-tasker/blob/dbf685fe2973c3490a27cbd37f31909ad5eb3bb3/app/src/main/java/com/termux/tasker/FireReceiver.java)
- [Android shared UID](https://developer.android.com/guide/topics/manifest/manifest-element#uid)
