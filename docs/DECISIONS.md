# Architecture Decision Log

## ADR-001: Start with correlated fault detection, not a flashy map
**Status:** Proposed baseline.
**Reason:** The operator can already diagnose individual customers in the OLT portal. Incremental value comes from automatic multi-customer detection, history and technician guidance.

## ADR-002: Modular Spring Boot monolith
**Status:** Proposed baseline.
**Reason:** Easier to build, run, test and maintain for an initial small-ISP deployment than microservices.

## ADR-003: Simulate before touching production
**Status:** Required initial milestone.
**Reason:** No interruption to subscribers; deterministic test cases; read-only and least-privilege integration later.

## ADR-004: Separate passive physical infrastructure from measured device telemetry
**Status:** Required design constraint.
**Reason:** A passive fiber segment cannot communicate health; confidence-based incident localization is an inference rather than direct measurement.

## ADR-005: EPON-specific Syrotech integration must be discovered empirically

**The installed build is recorded.** The Device Information screen identifies hardware `V1.2.4` and firmware `V2.03.77R`. The EPON management interfaces, numeric cause codes and raw log formats for this build still require verification.

## ADR-006 — Offline event-time replay

**Accepted for step 2.** One Java 21 engine owns the topology and alarm journal for one PON. Its runtime uses the JDK. An OLT visibility observation applies to each affected PON. Each engine retains the normalized alarms, original event IDs, event times, and receive times.

The replay clock follows receive time and can advance explicitly between alarms. Snapshots replay unique observations in deterministic event-time order, so an admissible late alarm can correct the history. Port and visibility signals are applied before ONU observations at the same instant. The default delivery allowance is 90 seconds. Future-dated and excessively delayed observations remain available in the journal and are excluded from state transitions. This in-memory design supports finite offline scenarios. Long-running collection will require a retention policy and bounded processing.

An engine reserves a public incident ID when it first returns the incident in a snapshot. On each replay, every original opening alarm counts once toward the outage it supports. A repeated LOS report counts toward the outage that owns the onset of that continuous loss. The outage with the unique highest count keeps the ID. If the highest counts tie or all opening alarms lose their match, the ID stays reserved and is absent from that snapshot. Later evidence can distinguish the outages.

The engine considers existing IDs in first-publication order. If two IDs identify the same outage, the earlier ID takes precedence and the other stays reserved. The engine then allocates fresh numbers to outages still lacking an ID. Timelines follow event time, and incident numbers follow first publication. Keeping links stable across process restarts will require persisting these bindings with the journal.

## ADR-007 — Evidence for inferred scope and recovery

**Accepted for step 2.** A domain starts with its in-service ONUs observed online and fresh immediately before the first eligible LOS. Its incident retains that denominator. The default opening rule requires at least three distinct newly affected ONUs and at least 75% of the eligible group in an inclusive rolling 90-second window. The initial freshness limit is five minutes. Eligibility excludes maintenance and decommissioned ONUs, along with ONUs whose pre-outage state was missing, stale, or offline.

Splitter parent links and incoming fiber segments define the candidate scopes. Evidence confined to a narrower path cannot establish a wider outage. Qualifying independent branches stay separate. A fully affected parent can absorb localized incidents when its window covers their outstanding losses across multiple paths. Superseded histories link to the surviving incident. Invalid connectivity disables optical inference for that PON. An observed port-down alarm has priority and preserves the preceding inferred history.

Inputs with exactly the same set of physically assigned ONUs form one fault domain. Its `candidateSegments` list contains the known segment IDs in upstream-to-downstream order. The deepest splitter ID remains the label for a proper subtree. A cascade through S2/F2 and S4/F4 can therefore report both F2 and F4 on one incident. The singular `inputSegment` is available only for a single identified splitter input. An unlabelled alternative leaves that field unknown. A PON-wide inference can include common feeder segments, while an observed port-down scope has an empty candidate list.

Recovery requires fresh online observations for all implicated ONUs, continuous visibility, and a configurable hold of 30 seconds. An observed port-down incident also requires an explicit port-up observation. Stale readings and OLT visibility loss produce unknown state. A field diagnosis is required to identify a physical break.

## ADR-008 — Local read-only replay API

The `api` module packages Spring Boot with the simulator and correlation engine in one executable JAR. The HTTP listener binds to `127.0.0.1`. It serves synthetic data from memory on the local machine.

At startup each named view feeds a fresh engine the simulator alarms received by its fixed UTC cutoff. The engine advances to that cutoff to evaluate recovery timers, and the API retains the resulting immutable view. A GET selects a view by name. Its `replayTimestamp` is the cutoff on the receive clock. Incident timeline entries use event time, including the deadline for stable recovery.

The `observed` object contains PON and ONU readings and a journal of normalized alarms. Each incident change includes its basis, scope, affected and eligible ONUs and counts, and evidence IDs referencing that journal. Candidate segments are inferred from optical topology. ONU keys and membership lists are sorted for repeatable JSON. The cascade uses the same LOS alarms as S2 with F4 downstream of F2.

## ADR-009 — Disconnect reasons in synthetic Syrotech snapshots

The `syrotech-adapter` module normalizes one finite replay for one OLT per call. It orders status rows by observation time and tracks each PON/ONU pair independently. Each result pairs the original row with an `Alarm`. The alarm uses the row ID, PON, ONU, observation time and receive time. The source retains the reason text and its reported disconnect time.

An online reading, including recovery, produces `ONU_ONLINE`. An offline reading can use an explicit reason only if its disconnect time falls after the last online reading and at or before the first offline reading of the current outage. For a replay that starts offline, the disconnect time must coincide with the first offline reading. A timestamp already seen before its claimed occurrence stays unusable for that ONU throughout the replay.

The caller also sets the maximum age of the reason at the time of the status reading. Tests use an inclusive 30-second limit. Both the age and the outage interval must match. The synthetic labels `LOS` and `Loss of Signal` map to `ONU_LOS`, and `Dying Gasp` maps to `ONU_DYING_GASP`. All other offline observations produce `ONU_OFFLINE_UNKNOWN`. Field validation must establish which native values and timestamps carry these meanings.

The adapter checks the complete replay for conflicting explicit causes within one uninterrupted offline interval. If eligible reports disagree between LOS and Dying Gasp, their normalized cause is unknown throughout that interval. Each original reason remains available for inspection. A generic link-loss label or a PON-level alarm also leaves an offline ONU's cause unknown. The related manual supplies fixture vocabulary. [Source notes](PROJECT_CONTEXT.md#references-for-the-syrotech-epon-adapter)
