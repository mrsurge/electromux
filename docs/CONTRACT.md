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
allowlisted: status, start, request, stop, detach. No shell strings or arbitrary
executables arrive from the page. Backend argv is selected by helper startup.

The backend's hello is semantic readiness. Its stdout is framed protocol;
stderr is diagnostics, inherited by the helper. Requests use the existing
backend process, never automatic mutation retries. The sample backend supports
only ping and emits a correlated event with the response. Timeouts and bounded
backpressure remain pending before long-running application workloads.

## Android execution gate

Primary candidate: Tasker-style explicit TermuxService ACTION_SERVICE_EXECUTE
with a background app-shell runner. TermuxService is non-exported; validate
actual shared UID/certificates and foreground-service eligibility first.
Public RUN_COMMAND is a separate optional adapter, not silent fallback.
Completion PendingIntent is not readiness or a streaming transport.

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
