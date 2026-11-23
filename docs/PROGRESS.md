# Development Progress — Roy Communication NOC

## Current status

**Steps 1 and 2 complete.** The Java 21 simulator and correlation engine run offline. The engine exposes observed ONU and port state, inferred fault scopes, and event-time incident histories.

## Next milestone

A read-only API for inspecting simulated devices and incidents is a proposed follow-up. Field integration depends on separate, authorized verification of the actual firmware and alarm semantics.

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

## To update after each Codex task
Record date, branch/commit, files changed, tested commands and results, decisions, known problems and the next proposed development task. Never place secrets or customer PII in this file.
