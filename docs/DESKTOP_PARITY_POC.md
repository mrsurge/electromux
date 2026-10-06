# Desktop-language integration POC

Approved direction, 2026-10-06. Electromux is a reusable Termux application host
on Android, with Cefrium as its rendering engine. TE2 Termux is its first real
consumer, separate from TE2 Cefrium and Gecko. The tested independent sample
remains a non-TE2 proof; consumer integration precedes polished SDK extraction.

## Reuse actual code, not just appearance

Use TE2 Desktop's request/event API and behavior as the reference. Reuse portable
launcher/settings browser code and pure JS/TS startup/state policies where
possible. Electron preload/main-process APIs need platform adapters; a JavaScript
engine cannot supply Node/Electron capabilities by itself. Introduce an optional
headless engine only after identifying a concrete reuse requirement. Do not
create a parallel remote/local connection system or copy a monolithic activity.

Source references in the TE2 repository:

- `desktop_client/electron/src/shared/contracts.ts` and shell preload.
- `desktop_client/android_shell/host.js` and settings (Electron-owned source).
- `local-framework-controller.ts`, configuration and preferred-app startup tests.
- Android persistent runtime service, framework relay, asset manager and Cefrium policies.

## Reusable host contract

Consumer configuration supplies application identity/branding (label, icons,
splash), packaged entrypoints/resources, declared asset/custom routes, guarded
consumer bridge methods, backend launch configuration and installation adapter.
Electromux supplies rendering, Termux execution, ownership/readiness, bounded
request/reply/events and lifecycle. TE2-specific methods, endpoints, catalog,
bookmarks and OTA/installer policy remain consumer code; no TE2 imports in core.
Serving a custom route is not permission to invoke native commands. Keep explicit
route/bridge allowlists, native credentials and trusted caller checks.

Selected remote endpoint and owned local process are independent state.
Remote-only operation needs no local backend install/start. Attach to an existing
local process as external; only actual launch grants ownership. Switching servers
retargets the existing relay. Exit stops owned processes, never external/remote
servers. Preserve consumer protocols: TE2's bootstrap stdin/FD3 control and
worker pipe readiness are not the sample protocol. Build/install and readiness
are separate phases; a long build must not trip a running-server deadline.

## Sequence and acceptance

Detailed source-backed inventory and implemented foundation:
[Desktop API inventory](DESKTOP_API_INVENTORY.md).

1. Consumer descriptor, method/event inventory and portable-code reuse map.
2. Branded TE2 Termux remote-only POC with packaged existing resources.
3. Local framework launch/attach/stop through helper, retaining desktop policies.
4. Automatic startup/preferred app and full TE2 Desktop feature parity, using
   explicit Android equivalents for platform-specific UI behavior.
5. Both-device acceptance: owned/external/remote, endpoint switching, failed or
   long build, background/recreation/renderer recovery, reconnect and shutdown.
6. Separately approved working POC publication, then SDK/library/plugin extraction
   and a differently branded non-TE2 consumer to prove reusable custom routes/APIs.

No full Electron API promise, dependency addition or implementation is approved
by this documentation pass. The current sample's 30-second idle expiry,
unsolicited event streaming and lifecycle gaps remain visible acceptance work.
Detailed consumer integration plan lives in TE2 `docs/apps/electromux/PLAN.md`.
