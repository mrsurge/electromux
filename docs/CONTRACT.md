# Prototype contract

Status: internal sample contract, version 1; not a stable SDK API.

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
unsolicited frames. Android's existing sample client remains request-only until
its socket reader and renderer lifecycle/event authorization are upgraded.

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
