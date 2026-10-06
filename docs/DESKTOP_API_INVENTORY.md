# Desktop API inventory and first bridge slice

Source inspection: 2026-10-06, TE2 `desktop_client/`. This is a reuse map, not a
claim that the independent sample implements TE2 Desktop functionality.

## Launcher: 21 native requests

Authority: `electron/src/shared/contracts.ts`, `preload/shell-preload.ts`, and
`main/index.ts`'s `nativeRequest` dispatcher.

| Family | Methods | Android implementation boundary |
| --- | --- | --- |
| Settings and bookmarks | `get_settings`, `save_settings`, `get_framework_bookmarks`, `upsert_framework_bookmark`, `delete_framework_bookmark` | TE2 consumer storage/policy; reusable browser UI |
| Endpoint and requests | `get_browser_framework_origin`, `framework_request`, `get_framework_status`, `get_fws_status` | Existing Android relay/service adapters; TE2-specific endpoints remain consumer-owned |
| Local backend | `get_local_framework_config`, `save_local_framework_config`, `get_local_framework_state`, `start_local_framework`, `stop_local_framework`, `use_local_framework` | Termux execution adapter; preserve ownership, stdin/FD3 and readiness semantics |
| Assets | `get_asset_status`, `update_assets` | TE2 asset manager/OTA policy, not generic Electromux core |
| Presentation | `navigate_app`, `view_action`, `window_control`, `set_window_title` | Cefrium/Android presentation adapters; explicit unsupported desktop-only capabilities |

The shell bridge also has `notifyReady` and five disposable subscriptions:
app navigation, asset update, local-framework state, steer and status.
Electron accepts launcher requests only from its current shell WebContents.
That identity guard must survive transport adaptation.

## App views: 28 commands, separate trust boundary

Authority: `electron/src/shared/app-view-contracts.ts` and
`preload/app-view-preload.ts`; native dispatcher validates current primary or
owned secondary contents and exact relay origin, then applies role restrictions.

- Inspection/navigation: `inspect`, `reload`, `home`.
- Identity: `read_client_identity`, `reset_client_identity`.
- Readiness/assets: `wait_for_app_prerequisites`, `force_asset_update`.
- Run targets: `register_run_target_surface`, `release_run_target_surface`.
- Preferences: `read_sidebar_presentation_state`, `write_sidebar_presentation_state`,
  `read_terminal_destination`, `write_terminal_destination`.
- Sidebar presentation: `open_sidebar_menu`, `place_sidebar_surface`,
  `detach_sidebar_surface`, `focus_sidebar_surface`, `refresh_sidebar_surface`,
  `close_sidebar_surface`, `reconcile_sidebar_surfaces`.
- Second editor: `open_second_editor`, `sync_second_editor_project`,
  `place_second_editor_surface`, `set_second_editor_dock_size`,
  `set_second_editor_mode`, `second_editor_ready`.
- Diagnostics: `set_projection_probe_enabled`, `inspect_projection_probe`.

Separate dialog open/close APIs and secondary-editor/sidebar event subscriptions
also exist. Android equivalents need an explicit capability matrix; an Android
drawer is not an Electron detached window. Do not expose launcher authority to
every embedded app page or fabricate successful unsupported operations.

## Actual code reuse candidates

| Source | Reuse assessment |
| --- | --- |
| `android_shell/launcher.js`, `settings.js`, extension modules | Browser code; retain actual source, inject platform bridge and adapt layout. Despite the name, this directory is Electron-owned. |
| `android_shell/host.js` | Browser code with `__te2DesktopNativeRequest`/WebKit reply plumbing; isolate that bridge boundary, not duplicate all settings logic. |
| `shared/native-request-contracts.ts` | Pure TypeScript result settlement/unwrapping, with tests. Compatible result semantics chosen below; actual shared-source integration still pending. |
| `main/preferred-app-startup.ts` | Injected startup sequencing is portable; Node `process.env`/types and TE2 endpoint policy need a consumer adapter. |
| `main/local-framework-controller.ts` | Behavior reference, not directly browser-portable: Node child_process/streams, health probing and FD3 control. |
| `renderer/index.ts` | Mostly DOM/browser code but desktop geometry and bridge assumptions; do not blindly transplant its five-second status poll. |

Cefrium runs these browser modules; no additional JavaScript engine is required
for this slice. Existing Android persistent service/relay remains the reuse
candidate for transport lifecycle. No new remote connection stack is justified.

## Implemented foundation (independent sample only)

`dev.mrsurge.electromux.host.ConsumerDescriptor` declares consumer ID/label,
entrypoint, packaged assets, exact page-to-asset routes, registered methods and
events. Native code constructs it; page input cannot replace it. APK ID, icon,
label resources and signing remain build-time configuration. This initial
implementation supports exact bundled `file:///android_asset/` pages only;
HTTP/custom route serving, icons/splash and production stable-origin hosting
are not implemented by a descriptor declaration.

`ConsumerPageProtocol` requires a complete handler registry, bounded strict
`{id, method, params}` requests, exact declared caller page, and declared events.
`SampleConsumer` registers helper actions/parameter validation; the generic host
has no TE2 imports, executable names, credentials or endpoint policy.
The native sample retains its serial bounded I/O executor and closes it with
the Activity. Helper wire protocol/authentication is unchanged.

Browser `ElectromuxBridge.create({query, methods, events})` exposes
`request(method, params)`, `on(event, callback)` returning an unsubscribe function,
and `dispose()`. Replies carry `{id, result: {ok:true,value} | {ok:false,error},
events:[{name,payload}]}`. Result semantics match Desktop's native result envelope,
but desktop modules have **not yet been imported or extracted** into this sample.
Frontend allowlists are ergonomics; native registration is the security boundary.

Requests are limited to 4 KiB, replies to 64 KiB, eight pending calls by default,
and sixteen reply-associated events. Timeout/disposal rejects pending promises;
late replies are ignored. Neither action retries or proves cancellation of an
already dispatched mutation. Event listener errors cannot strand request results.
Current events arrive with a correlated reply, **not unsolicited native streaming**.
Pagehide disposes the browser client. Renderer/recreation ownership and native
push delivery require a later lifecycle slice and physical acceptance.

## Next consumer slice

Use the actual Desktop launcher/settings browser sources behind a TE2 consumer
adapter, package them in a distinct TE2 Termux target, and prove remote-only
connection/navigation through the existing Android relay. Only then add local
framework execution and the remaining presentation/event capabilities. No
Electron API completeness, APK/device acceptance or publication follows here.
