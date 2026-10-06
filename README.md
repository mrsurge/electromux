# Electromux

Experimental framework-agnostic Android application host: Cefrium renders a
consumer's bundled frontend; a Termux helper connects it to an owned backend.
This is an initial scaffold, not a shipping SDK or a replacement for Cefrium.

TE2's future consumer is **TE2 Termux**, a separate APK alongside TE2 Cefrium
and TE2 Gecko. This sample has its own application ID `dev.mrsurge.electromux.sample`.
No TE2 imports or application-specific services belong in the host core.

## Implemented in this checkpoint

- Bounded length-prefixed control frames using standard-library Python.
- Local Unix-socket helper with session/token handshake and a single owned
  sample backend, explicit status/start/request/stop/detach and reconnect.
- Executable independent backend and real subprocess/socket regression tests.
- Android/Cefrium Gradle and activity scaffold with a bundled sample page.

The Android sample has native identity inspection and an explicit diagnostic
Termux launch adapter (`python --version`) and a restricted bundled-page helper
bridge. Native code provisions the bundled Python helper into a sample-owned
Termux cache directory, retains credentials and performs bounded authenticated
filesystem-socket requests. A correlated native completion observer displays
bounded stdout/stderr, exit status and Termux errors, with a 30-second timeout
and no automatic retry. Signing/shared UID,
renderer lifecycle and the complete round trip have separately recorded
acceptance gates. See [intent security notes](docs/INTENT_SECURITY.md) and the tracker.

## Run the host-independent tests

```sh
python3 -m unittest discover -s tests -v
```

Tests create isolated temporary directories and processes; no shared runtime
or installed Termux state is used. They work on ordinary Linux and should be
repeated inside Termux before device acceptance.

## Android scaffold

From `android/`, use the pinned Gradle wrapper with JDK 25 and SDK 37. No
signing key is committed. Set `ELECTROMUX_KEYSTORE`, `ELECTROMUX_STORE_PASSWORD`,
`ELECTROMUX_KEY_ALIAS`, and `ELECTROMUX_KEY_PASSWORD` to a deliberately selected
GitHub-Termux-compatible key for device builds. The build rejects APK tasks
without explicit signing configuration; ordinary auto-generated debug keys
cannot establish the shared UID contract.

The manifest declares `com.termux` shared UID on the root. Actual installed
UID/signature and modern Android behavior must still be proven; do not install
over another client's application ID. Never uninstall Termux to accommodate
a test APK without explicit user approval and backup.

## Architecture and next steps

See [CONTRACT.md](docs/CONTRACT.md) and [TRACKER.md](docs/TRACKER.md).
The sample control codec is JSON; consumer payload transport is not yet a
public contract. TE2 integration must preserve existing MessagePack boundaries.
No generated HTML/srcdoc delivery is planned. Existing assets load locally;
backend events update the running page instead of replacing its document.

The Cefrium reference pin is in `vendor/cefrium-source.json`. A nested source
submodule and published Gradle/Maven libraries are later integration work, not
implemented by that metadata file.

Repository licensing must be selected before public distribution; this private
scaffold makes no license grant. Gradle wrapper files retain upstream notices.
