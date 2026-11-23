# Development Progress — Roy Communication NOC

## Current status

**Step 1 complete.** The Java 21 `simulator` module replays two synthetic ONU event sequences offline.

## Next milestone

Use the simulator fixtures to build and test the offline correlation engine. The broader offline milestone includes the following work.

- In-memory optical topology including cascaded splitter branches.
- Deduplicated state/event ingestion.
- Rule-based shared-outage candidate identification.
- Incident creation, update, suppression and recovery.
- Read-only REST API to inspect simulated devices/incidents.
- Automated unit/integration tests for normal and adversarial scenarios.

## Acceptance criteria
- LOS on 8 of 8 eligible ONUs beneath a shared branch triggers one branch incident if an adjacent branch stays healthy.
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

## To update after each Codex task
Record date, branch/commit, files changed, tested commands and results, decisions, known problems and the next proposed development task. Never place secrets or customer PII in this file.
