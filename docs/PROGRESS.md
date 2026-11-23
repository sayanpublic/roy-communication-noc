# Development Progress — Roy Communication NOC

## Current status

The Java 21 simulator, correlation engine, and local read-only Spring Boot API run on synthetic data. The API exposes six fixed views of observed ONU and PON state and inferred incident history. The Network Replay dashboard at the API root displays the topology, incident timeline and observation journal. See [startup and example requests](#2026-11-23--local-read-only-replay-api).

The offline Syrotech EPON adapter accepts synthetic ONU status snapshots and emits the correlation engine's normalized alarms. The installed OLT reports hardware `V1.2.4`, firmware `V2.03.77R` and build date `2025-08-29`. [Hardware and source notes](PROJECT_CONTEXT.md#references-for-the-syrotech-epon-adapter)

## Next milestone

When a redacted offline ONU row or existing alarm entry becomes available, compare its reason and timestamps with the adapter's assumptions. Numeric SNMP cause codes and raw log formats for the installed firmware remain open.

## Acceptance criteria
- The sixth S2 LOS opens one inferred incident at 6 of 8 eligible ONUs. It updates through 8 of 8 and resolves after stable recovery.
- Individual offline ONU does not trigger a multi-customer incident.
- Dying Gasp and OLT-unreachable scenarios do not produce a false confirmed fiber-break diagnosis.
- Unknown or stale telemetry is marked unknown.
- Out-of-order events and duplicate alerts do not create duplicate incidents.
- Tests run locally and their results are recorded here.

## Milestone history
- Initial repository context prepared; no production device integration performed.

### 2026-11-23 — Offline OLT simulator

Development branch `codex/dev-olt-simulator`, based on `main` at `524017c`. Added the root and simulator Maven POMs, `OltSimulator.java`, `OltSimulatorTest.java`, and `.gitignore` entries for build output and `.env`.

Print both scenarios from the repository root using JDK 21.

```sh
java simulator/src/main/java/in/roycommunication/noc/simulator/OltSimulator.java
```

| Scenario and event ID prefix | Sequence on synthetic `PON-03` | Events |
| --- | --- | ---: |
| `branch-los` | All 16 ONUs start online. S2 (`ONU-01` through `ONU-08`) reports LOS over 35 seconds. S3 (`ONU-09` through `ONU-16`) stays online. Each S2 ONU then reports `ONU_ONLINE` during recovery. | 32 |
| `dying-gasp` | A fresh snapshot starts all 16 ONUs online. Only `ONU-09` reports `ONU_DYING_GASP`, ending the replay. | 17 |

Each CSV row carries `eventId`, `ponPort`, `onuId`, `eventType`, `eventTimestamp`, and `receiveTimestamp`. Both clocks use fixed UTC instants with a simulated 250 ms delivery delay. The ONU assignments, timing, and normalized event types are synthetic. Actual Syrotech alarm semantics still require firmware verification.

Run the tests from the repository root.

```sh
mvn test
```

With JDK 21.0.10 and Maven 3.9.14, `mvn test` and `mvn -o test` each passed all four tests with zero failures, errors, or skips. The tests check both scenario sequences, unique IDs, PON assignment, timestamp ordering and delay, and repeatable CSV output. Two separate runs of the print command produced identical output with one header and 49 events.

### 2026-11-23 — Offline fault correlation

Development branch `codex/dev-olt-simulator`, based on simulator commit `2294df7`. Extended `OltSimulatorTest` to assert exactly 35 seconds between the first and eighth LOS event timestamps. Before engine implementation, `mvn test` passed all four simulator tests.

Added the `correlation` Maven module to the root build. It contains the normalized alarm model, validated PON topology, configurable rules, event-time replay, and incident history. The simulator is a test dependency. The replay tests feed `branchLos()` and `dyingGasp()` into separate engines. The design and operating assumptions are recorded in the [decision log](DECISIONS.md).

Run `mvn test` from the repository root to reproduce the suite and print the S2 timeline. The completed build passed **28 tests** with zero failures, errors, or skips. Four cover the simulator and 24 cover the engine, topology, and end-to-end replays. `mvn -o test` also passed all 28 using cached build dependencies.

**S2 replay on 2026-01-01.** All rows refer to incident `PON-03/S2/1`. The inferred scope is splitter S2 and its input path F2. The eight S3 ONUs stay online.

| Event time (UTC) | Incident change | Affected / eligible |
| --- | --- | ---: |
| 00:00:55 | Sixth distinct ONU reports LOS. The S2 incident opens. | 6 / 8 |
| 00:01:00 | Seventh ONU reports LOS. | 7 / 8 |
| 00:01:05 | Eighth ONU reports LOS. | 8 / 8 |
| 00:02:07 | Last affected ONU reports online. The recovery hold begins. | 0 / 8 |
| 00:02:37 | Replay clock reaches 30 seconds of stable recovery. The incident resolves. | 0 / 8 |

The suite also checks isolated LOS and Dying Gasp, PON-wide clusters, direct port-down precedence, stale and unreachable telemetry, frozen pre-outage eligibility, duplicate and late delivery, overlapping and independent groups, missing and invalid connectivity, and interrupted recovery. The history is process-local and sized for finite replays. Thresholds, freshness, and recovery timing still need field calibration.

### 2026-11-23 — Incident IDs and cascaded fiber segments

Follow-up to the review of `80f8324` on `codex/dev-olt-simulator`. The two new reproduction cases initially showed the current S2 outage moving from `/1` to `/2` and a two-input cascade reporting only F4. Updated `CorrelationEngine`, `IncidentReplay`, `PonTelemetry`, and `FiberTopology`, along with the engine, topology, and simulator replay tests.

- **Same-splitter backfill.** The current incident opens at replay second 66. Alarms received at second 75 reveal a separate S2 outage that opened at second 11 and resolved at second 51. The test checks the current ID after each late delivery, the recovered episode's distinct ID, and equality of both histories after duplicate delivery. Another test moves an ongoing outage's opening from second 46 to second 16 and checks its ID survives the correction.
- **Indistinguishable paths.** S2/F2 and S4/F4 serving the same eight ONUs produce one inferred incident with both candidate segments throughout its history. A further test covers an unlabelled input in that chain. The standard S2 replay still lists F2 alone and resolves at `2026-01-01T00:02:37Z`. The whole-PON test also checks the common feeder candidate and the observed port-down scope.

From the repository root, `mvn test` and `mvn -o test` each passed **32 tests** with zero failures, errors, or skips. Four cover the simulator and 28 cover correlation, topology, and replay. ID reservations live in the engine's memory. The [decision log](DECISIONS.md) records the scope and identity semantics for a future API.

### 2026-11-23 — Incident identity when opening alarms split across outages

Follow-up to `f25f705` on `codex/dev-olt-simulator`. The new regression in `CorrelationEngineTest` reproduced two cases where the first matching alarm incorrectly decided which S2 outage inherited `PON-03/S2/1`. `IncidentReplay` now counts all six original alarms in this fixture, including LOS reports that late observations trace to an earlier onset of the same loss.

| Original alarms in resolved outage | Original alarms in active outage | Resolved incident | Active incident |
| ---: | ---: | --- | --- |
| 1 | 5 | `PON-03/S2/2` | `PON-03/S2/1` |
| 3 | 3 | `PON-03/S2/2` | `PON-03/S2/3` |
| 5 | 1 | `PON-03/S2/1` | `PON-03/S2/2` |

In the tie, the original ID stays reserved. Replaying every alarm in reverse delivery order preserved both incident histories and the observation journal in all three cases. From the repository root, `mvn test` and `mvn -o test` each passed **35 tests** with zero failures, errors, or skips (4 simulator and 31 correlation). The next milestone is the local read-only API for simulated data.

### 2026-11-23 — Local read-only replay API

Based on `6bb3d36` on `codex/dev-olt-simulator`. The root build includes the new `api` module. It uses Spring Boot 3.5.16, the simulator's branch inventory and event streams, and the existing correlation engine. Each response identifies its replay cutoff. Incident evidence IDs reference entries in `observed.alarms`, which carry both device and receive timestamps.

From the repository root in PowerShell with JDK 21 and the Maven dependencies cached, build and start the API.

```powershell
mvn -o package
java -jar api/target/api-0.1.0-SNAPSHOT.jar
```

In a second PowerShell terminal, list the views and request each one. Use Ctrl+C in the server terminal to stop it.

```powershell
curl.exe --noproxy "*" --fail http://127.0.0.1:8080/api/replays
curl.exe --noproxy "*" --fail http://127.0.0.1:8080/api/replays/s2-open
curl.exe --noproxy "*" --fail http://127.0.0.1:8080/api/replays/s2-resolved
curl.exe --noproxy "*" --fail http://127.0.0.1:8080/api/replays/dying-gasp
curl.exe --noproxy "*" --fail http://127.0.0.1:8080/api/replays/cascade-open
```

| View | Replay cutoff on 2026-01-01 (UTC) | What to inspect |
| --- | --- | --- |
| `s2-open` | 00:00:55.250 | S2 opens at 6 of 8 eligible ONUs. F2 is the sole candidate and S3 stays online. |
| `s2-resolved` | 00:02:37 | The same incident reaches 8 of 8 at 00:01:05, enters recovery at 00:02:07, and resolves after the 30-second hold. |
| `dying-gasp` | 01:00:30.250 | ONU-09 reports Dying Gasp. The other 15 ONUs are online and the incident list is empty. |
| `cascade-open` | 00:00:55.250 | One inferred incident lists both F2 and F4 because they serve the same eight ONUs. |

From the repository root, `mvn test` and `mvn -o package` each passed **41 tests** (4 simulator, 31 correlation and 6 HTTP integration) with zero failures, errors, or skips. The HTTP tests cover all four views, evidence references, interleaved GETs, a 404 for an unknown view, and 405 responses to POST, PUT, and DELETE. The root build pins Maven JAR Plugin 3.4.2, the version used by Spring Boot 3.5.16, for offline packaging.

Two independent launches of the packaged JAR each bound to `127.0.0.1:8080`. The index listed the four documented views, and the JSON body for each view matched across both launches. The API serves these checkpoints from memory. The [decision log](DECISIONS.md#adr-008--local-read-only-replay-api) records how replay cutoffs and incident timestamps are selected.

### 2026-11-23 — Offline Syrotech EPON snapshot adapter

Based on `6dfbdfb` on `codex/dev-olt-simulator`. The new `syrotech-adapter` Maven module accepts typed `OnuStatusSnapshot` rows. Pass the rows from one OLT replay to `SyrotechEponAdapter.normalize`. Each result exposes the original snapshot and an `alarm()` ready for the appropriate PON's `CorrelationEngine.accept`.

In the regression replay an ONU disconnects at second 4, recovers at second 7, and is offline again at second 10. Reusing the first reason produces `ONU_OFFLINE_UNKNOWN` even though the reason is only six seconds old. The test covers both LOS and Dying Gasp. An end-to-end replay also checks that three stale LOS reasons open zero incidents and three fresh LOS reasons after recovery open one S2 incident. The [decision log](DECISIONS.md#adr-009--disconnect-reasons-in-synthetic-syrotech-snapshots) records the evidence rules.

The fixtures include the related manual's `ONU Link LOST` and `Dying Gasp` labels. Further tests cover ambiguous text, contradictory causes, missing timestamps, old and future-dated evidence, shuffled input and identical ONU IDs on different PONs. These tests use synthetic rows throughout.

From the repository root, `mvn -o -pl syrotech-adapter -am test` passed **55 tests** with cached dependencies. The full `mvn test` run passed **61 tests** (20 adapter, 31 correlation, 4 simulator and 6 local HTTP) with zero failures, errors, or skips.

### 2026-11-24 — LOS confirmed by a later poll

On `codex/dev-olt-simulator` at `fab19a1`, the new adapter-to-engine test reproduced the missed incident. Three S2 ONUs reported LOS on a later poll and the engine opened zero incidents. Changes to `CorrelationEngine`, `IncidentReplay`, and `PonTelemetry` let a fresh LOS confirmation support the original offline interval. [Correlation timing and evidence](DECISIONS.md#adr-007--evidence-for-inferred-scope-and-recovery)

| Replay seconds | Observations and incident history |
| --- | --- |
| 10, 11, 12 | Three of four eligible S2 ONUs go offline with unknown cause. Their second poll at 15 also reports unknown. |
| 20–23 | Two ONUs confirm LOS at seconds 20 and 22. The first ONU repeats LOS at 21 and 23. The incident list is still empty. |
| 24 | The third ONU confirms LOS. One inferred S2 incident appears at 3 of 4. The replay dates its opening to second 12 and cites the LOS polls at 20, 22 and 24. The fourth S2 ONU and the S3 ONU stay online. |
| 25 | Further LOS polls and duplicate deliveries preserve the incident ID, count and opening time. |

`SyrotechEponAdapterTest` verifies that three delayed stale reasons open zero incidents. A pair of replays places the first and last offline readings 90 and 91 seconds apart. The 90-second group qualifies with confirmations 103 seconds apart. The 91-second group opens zero incidents with confirmations 85 seconds apart. `CorrelationEngineTest` covers recovery, Dying Gasp, loss of visibility, telemetry freshness, and the delivery deadline for confirming evidence.

From the repository root, `mvn -o -pl syrotech-adapter -am test` passed **64 tests**. The full `mvn test` run on Java 21 passed **70 tests** (4 simulator, 37 correlation, 23 adapter and 6 local HTTP) with zero failures, errors or skips. These replays use synthetic observations. Native alarm formats for the installed firmware still need a redacted field sample.

### 2026-11-24 — Network Replay dashboard

Build using `mvn package`, run `java -jar api/target/api-0.1.0-SNAPSHOT.jar`, and open [the local dashboard](http://127.0.0.1:8080/). The dashboard reads the existing replay API. Select a scenario and checkpoint to inspect the topology, affected counts and evidence. Selecting an ONU filters the journal. Candidate segments are inferred from the simulated ONUs and topology.

The delayed LOS fixtures go through the Syrotech adapter and the correlation engine. The pending view shows the three unknown causes without a shared incident. The confirmed view shows one incident affecting 3 of 4 eligible ONUs, with a historical opening at 00:00:12 UTC and the last opening evidence received at 00:00:24.250 UTC.

The Java 21 Maven suite passed **72 tests**, including eight HTTP integration tests. Browser checks covered recovery, the delayed LOS checkpoints, evidence filtering and cascaded candidate segments.

## To update after each Codex task
Record date, branch/commit, files changed, tested commands and results, decisions, known problems and the next proposed development task. Never place secrets or customer PII in this file.
