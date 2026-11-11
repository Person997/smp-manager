# SMP Manager 1.1.2 validation

Validated locally on Windows, 8 November 2026, with Java 25.0.5 and Paper 26.3 build 244.

## Automated suite

`gradle build` passed: **43 tests, zero failures, zero errors, zero skipped**.

| Area | Tests |
| --- | ---: |
| Lifecycle, thresholds, queue, reservations, override, recovery | 17 |
| Configuration validation and permission separation | 5 |
| Warning milestones, stale counts and duplicate prevention | 4 |
| Verification and legacy database compatibility | 5 |
| Menu replies, duplicate actions, connection replacement and shutdown races | 8 |
| Menu-close acknowledgement, timeout and disconnect ordering | 4 |

Checks include UUID deduplication between Minecraft and Discord; opening and staying-open thresholds; join window; configurable normal/override timers; repeat-autopass prevention; graceful stop versus unexpected exit; no instant close/reopen loop; fresh versus stale player samples; one-to-one links; single-use expiring codes; persistent attempt throttling; same-admin manual confirmation; revocation; and reading the existing SQLite migration and HMAC code format.

## Actual Paper integration test

A separate copied flat test world ran on a loopback-only test port. The real SMP world was not used for accelerated countdowns. The override grace was set to 12 seconds for this test; the real installation retains 3600 seconds.

- Paper loaded exactly one plugin, SMPManager. The shaded Discord and SQLite dependencies loaded successfully.
- Closed-world game time remained **212711 ticks** across two queries 2.5 seconds apart. Console commands and plugin tasks continued to respond.
- `/smp autopass` resumed simulation: the final rerun advanced from **212711** to **212755 ticks**.
- At low population, each real chat warning from **10 seconds through 1 second** appeared exactly once. The timer closed and froze the world while its Java process remained alive.
- A second autopass followed by `/smp close` saved and froze the world again. In the final 1.1.2 rerun, its game time remained **212981 ticks** across the closed interval.
- The embedded bot connected to Discord, registered `/close` with the Manage Server default permission, and updated the existing persistent status message to SMP Manager / frozen while world simulation was stopped.
- The console `stop` command completed normal Paper world saving and exited with code 0.

The tests emitted test-session opening/closing notices through the existing bot. They did not modify real account associations.

## Native menu handler regression check

An isolated test-only Paper plugin supplied simulated configuration connections to the actual SMP Manager event handlers. It inspected Paper's generated native dialog data, rather than replacing the production menu code. The test-only plugin is not included in the download or installed in the real SMP server.

Verified: exact **World closed** headline; native `after_action=none` on both screens; Commands, status and Back flows; duplicate Run suppression; late responses not reopening a menu after Back; clear-dialog, then client acknowledgement, then disconnect; short **Disconnected.** reason; asynchronous native connection-close cleanup for both initial connections and returning players; an old connection not removing its replacement's queue presence; and whitelist removal disconnecting a waiting account. The fixture's whitelist change is restored before the world-control tests. An intentional 8.5-second stall on the isolated Paper main thread confirmed that a request times out after eight seconds and its canceled action does not execute when ticking resumes. This stall was not applied to the actual SMP world.

The acknowledgement check addresses a real client regression: sending clear-dialog and disconnect back-to-back could leave the custom screen visible after the network had ended. A server-side assertion that clear was sent first did not establish that the client had processed it. The fixture now delays its acknowledgement and emits the native connection-close event on a later tick.

The review also changed pending main-thread calls to cancel when their caller times out, prevented cross-dimension movement from using an invalid location-distance calculation, and kept a single client's dialog/network failure from faulting the entire world.

## Local deployment checks

- The previous four-component stack was gracefully stopped and backed up before migration.
- The actual survival world, whitelist, SQLite account/history data and private verification key were copied into the new direct Paper server.
- The old backup database passed SQLite integrity checking. Legacy links and status-message reuse are also covered by the compatibility test.
- The final JAR was tested in a local Paper instance with the embedded bot configured. The former lobby, backend and controller ports are no longer required.
- Public downloads contain the unconfigured properties example, not the local server, token, verification key, account database or world.

## Limits

This does not certify a particular PebbleHost plan: no hosted allocation was accessed. The host must offer the target Paper and Java versions and allow the plugin's outbound Discord connections.

Human client checks confirmed the Minecraft/autopass/Discord-close flow and that version 1.1.2's Disconnect button exposes a working Back to Server List button. The generated menu data and production handlers are also covered by the regression probe. No five-human load test or arbitrary other-plugin compatibility test has been performed. The `/close` and `/smp close` paths use the same permission-checked handler. A 12-second live timer plus simulated-clock tests cover the default one-hour override without waiting an hour. These checks do not establish that every possible bug is absent.

Paper reports unavailable Windows performance counters in this environment; startup and the plugin checks still complete. The plugin has not changed Windows counter settings.
