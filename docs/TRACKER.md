# Electromux independent sample tracker

- [x] Private independent repo and consumer-neutral layout.
- [x] Framing and real Unix-socket/subprocess sample tests.
- [x] Android/Cefrium build and bundled-page scaffold.
- [ ] Select repository license before public distribution.
- [ ] Add pinned Cefrium source submodule; prove SDK/plugin/resource combination.
- [ ] Verify signed sample APK and installed shared UID on Motorola/Pixel.
- [ ] Implement explicit Termux launch adapter and installer progress interface.
- [x] Add native UID/signature/service inspection and diagnostic launch adapter.
- [x] Compile Android sources and pass 3 launch-contract JVM tests plus 10 Python regressions.
- [ ] Verify diagnostic command completion on a signed, installed device sample.
- [ ] Wire Android filesystem socket client/native bridge to helper.
- [ ] Implement local-origin asset hosting; no server fetch dependency.
- [ ] Bound socket/backend timeouts, queues and cancellation; concurrent clients.
- [ ] Test activity/renderer recreation, lock/background and reconnect.
- [ ] Test install/upgrade/failure rollback/uninstall preserving user state.
- [ ] Complete frontend-to-backend-to-frontend device acceptance.
- [ ] Add TE2 Termux consumer as a separate app; existing clients unchanged.

Checked items indicate scaffold/test scope only, not complete native acceptance.

Next gate: [signed-device diagnostic procedure](DEVICE_LAUNCH_PROCEDURE.md).
Reconnecting devices and explicit build/install approval are prerequisites.
