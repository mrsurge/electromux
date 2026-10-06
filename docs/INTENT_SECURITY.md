# Best Practices and Security Alignment Update

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
