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
**Status:** Pending firmware confirmation.
**Reason:** Exact MIBs, alarms and management APIs are vendor/firmware dependent; GPON commands may not apply.

## ADR-006 — Offline event-time replay

**Accepted for step 2.** One Java 21 engine owns the topology and alarm journal for one PON. Its runtime uses the JDK. An OLT visibility observation applies to each affected PON. Each engine retains the normalized alarms, original event IDs, event times, and receive times.

The replay clock follows receive time and can advance explicitly between alarms. Snapshots replay unique observations in deterministic event-time order, so an admissible late alarm can correct the history. Port and visibility signals are applied before ONU observations at the same instant. The default delivery allowance is 90 seconds. Future-dated and excessively delayed observations remain available in the journal and are excluded from state transitions. This in-memory design supports finite offline scenarios. Long-running collection will require a retention policy and bounded processing.

An engine reserves a public incident ID when it first returns the incident in a snapshot. On later replays it matches the original opening alarms to their episodes before assigning numbers to newly discovered outages. Repeated LOS reports can trace back to an earlier onset of the same continuous loss. Timelines follow event time, and incident numbers follow first publication. An ID whose evidence disappears stays reserved. Keeping links stable across process restarts will require persisting these bindings with the journal.

## ADR-007 — Evidence for inferred scope and recovery

**Accepted for step 2.** A domain starts with its in-service ONUs observed online and fresh immediately before the first eligible LOS. Its incident retains that denominator. The default opening rule requires at least three distinct newly affected ONUs and at least 75% of the eligible group in an inclusive rolling 90-second window. The initial freshness limit is five minutes. Eligibility excludes maintenance and decommissioned ONUs, along with ONUs whose pre-outage state was missing, stale, or offline.

Splitter parent links and incoming fiber segments define the candidate scopes. Evidence confined to a narrower path cannot establish a wider outage. Qualifying independent branches stay separate. A fully affected parent can absorb localized incidents when its window covers their outstanding losses across multiple paths. Superseded histories link to the surviving incident. Invalid connectivity disables optical inference for that PON. An observed port-down alarm has priority and preserves the preceding inferred history.

Inputs with exactly the same set of physically assigned ONUs form one fault domain. Its `candidateSegments` list contains the known segment IDs in upstream-to-downstream order. The deepest splitter ID remains the label for a proper subtree. A cascade through S2/F2 and S4/F4 can therefore report both F2 and F4 on one incident. The singular `inputSegment` is available only for a single identified splitter input. An unlabelled alternative leaves that field unknown. A PON-wide inference can include common feeder segments, while an observed port-down scope has an empty candidate list.

Recovery requires fresh online observations for all implicated ONUs, continuous visibility, and a configurable hold of 30 seconds. An observed port-down incident also requires an explicit port-up observation. Stale readings and OLT visibility loss produce unknown state. A field diagnosis is required to identify a physical break.
