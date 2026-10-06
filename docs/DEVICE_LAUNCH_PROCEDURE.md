# Signed-device diagnostic launch gate

Scope: prove the existing native-configured diagnostic adapter on one device,
then repeat on the other. This is not page/helper bridge or TE2 acceptance.
Build/install/device changes require explicit approval before execution.

## Prerequisites

- Reconnect Motorola and Pixel; record exact ADB serial, Android version and
  ABI. Select the first target explicitly. No device was visible on 2026-10-06.
- Inspect the installed `com.termux` package's UID, version, signing certificate
  and service availability. Do not infer signing family from the app's name.
- Select a deliberately compatible signing key and compare its certificate
  with installed Termux before assembly. Supply `ELECTROMUX_KEYSTORE`,
  `ELECTROMUX_STORE_PASSWORD`, `ELECTROMUX_KEY_ALIAS` and
  `ELECTROMUX_KEY_PASSWORD` outside Git; never log passwords or commit keys.
  A public GitHub-Termux key cannot match F-Droid signing automatically.
- Confirm ordinary Termux Python exists at
  `/data/data/com.termux/files/usr/bin/python`; the diagnostic uses no venv.
- Use JDK 25, SDK 37 and the pinned Gradle wrapper. Confirm at least 2 GB free.
  The development machine had approximately 19 GB free at this checkpoint.

## Procedure after approval

1. Preserve existing apps and user data. Inspect whether
   `dev.mrsurge.electromux.sample` already exists and its signer. Stop on an
   incompatible existing package; do not uninstall Termux or reset app data.
2. Rerun Python regressions and Android compilation/JVM tests, then assemble
   the explicitly signed debug sample from `android/` with `:sample:assembleDebug`.
3. Inspect the final APK's package ID, root shared UID declaration, signing
   certificate, ABI and hash. Confirm it remains separate from all TE2 clients.
4. Install only on the approved exact ADB serial. Re-read installed UIDs and
   certificates for the sample and Termux; require actual matching identity.
5. Launch the sample and use **Check Termux identity**. All four facts must
   pass: installed, same UID, same signature, service available.
6. Capture bounded device/Termux execution evidence, then tap **Test Termux
   execution** once. Verify the explicit `python --version` command actually
   completes using service/shell records or logs. The sample currently reports
   dispatch only: that UI message alone is not completion evidence. If available
   logs cannot establish completion, stop and plan a narrow completion-observation
   slice rather than claiming success or repeatedly dispatching commands.
7. Record APK/source hashes, device/OS, identity results, command/exit evidence,
   exceptions and any foreground-service restriction. Repeat on the second
   device only after the first result is understood.

## Pass / stop conditions

Pass requires matching installed identity, an eligible service launch and
observed diagnostic completion. Compilation and `startForegroundService`
returning do not prove this gate. Stop on signature/UID mismatch, inaccessible
service, missing Python, foreground restriction or ambiguous completion.
No public RUN_COMMAND fallback, Termux wipe, shared-framework restart or TE2
APK replacement is authorized by this procedure.

After this gate, separately approve the authenticated filesystem socket/native
bridge slice: start the helper, handshake, ping/event round trip, disconnect and
reconnect to the same owned backend, then explicit owned shutdown. Asset hosting,
renderer recovery and installer rollback remain subsequent gates.
