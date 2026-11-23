# Roy Communication NOC — Project Context

## Context from ChatGPT design discussions
Roy Communication is a local broadband service business in Katwa, West Bengal, India. It provides internet connectivity using multiple upstream providers including Alliance, GTPL, and a provider the owner calls "CITY" (official entity/name not yet confirmed). The operator has administrator access to a mixture of OLTs and routers/switches, and can currently use an OLT management portal to inspect individual customer ONU online/offline state and optical signal readings.

### Identified hardware
- **OLT:** Syrotech **SY-GOPON-4OLT-L3**, a 4-PON-port EPON/GEPON OLT (the system must NOT assume GPON protocols). Exact firmware and supported MIBs, traps, syslog formats, CLI and security capabilities remain **unverified** on the actual device.
- **Router:** MikroTik RouterBOARD visible in the rack photo, precise model unverified.
- Passive fiber distribution: OLT -> feeder/distribution fiber -> passive optical splitter(s) -> customer ONU/ONT. There is generally no electronic monitoring device at each splitter.
- Customers, segment lengths, splitter hierarchy, exact routes, addresses, and ONU-to-branch associations are not yet collected. Fictional example counts in ChatGPT were illustrative, **not field measurements**.

## Business goal
Move from reactive one-by-one complaint diagnosis through the OLT portal to useful automatic operations: correlate multiple ONU losses; notify about shared outages; identify the probable upstream shared fault domain; preserve fault and repair history; monitor optical-power trends; and show relevant poles, fiber segments, splitters and customer locations on a private map. Avoid building an ornamental GIS dashboard without reliable telemetry/topology.

## Core technical principle
Physical cables and optical splitters are passive. Without additional optical test equipment, software cannot directly measure segment-by-segment physical health or pinpoint exact fiber breaks. It can infer likely *fault domains* from which ONUs report LOS, which remain healthy, the physical graph, and port/equipment alarms. Labels must distinguish observed status, suspected cause and verified field diagnosis. In-service OTDR/remote fiber testing is a separate future hardware-dependent capability.

## Proposed stack
- Java 21 + Spring Boot, modular monolith
- React + TypeScript + Leaflet; OpenStreetMap-compatible tiles with suitable production provider and usage policy
- PostgreSQL + PostGIS for topology and geographic geometry
- Synthetic OLT event simulator first; later read-only SNMP polling/traps, syslog, and/or vendor-specific read-only CLI/API via adapters when confirmed
- Optional Zabbix integration if it reduces operational complexity
- Docker Compose development/deployment; private authentication and access controls

## Topology model
Represent independently:
1. **Physical geography:** pole points, distribution boxes, splitter positions, route LineStrings, cable IDs and segments, splices, endpoints.
2. **Optical connectivity:** OLT/PON -> segments -> splitters (including port connectivity) -> drop segments -> ONUs. Support cascaded splitters and precomputed descendants for efficient evaluation.
3. **Logical service:** ISP association, VLAN/uplink/PPPoE metadata where accessible and relevant; never assume upstream operator controls all subscriber sessions.
4. **Telemetry:** device availability; per-ONU online/offline, last disconnection cause, optical power if exposed; port health; raw alarms and event timestamps.
5. **Incidents:** probable fault domain, observed evidence, affected/eligible customer counts, confidence, status, notifications, repair notes, and recovery.

## Proposed normalized events (verify against actual firmware)
`ONU_LOS`, `PON_LOS`, `ONU_DYING_GASP`, `ONU_OFFLINE_UNKNOWN`, `ONU_ONLINE`, `PON_PORT_DOWN`, `OLT_UNREACHABLE`. Distinguish vendor-native alarms from normalized categories. Dying Gasp can suggest power loss if reported; absence of Dying Gasp does not prove optical failure. EPON alarm codes and metadata MUST be verified against the actual device.

Normalized fields: eventId, device event time, receive time, oltId, ponPort, onuId or serial, normalized type, native alarm/cause, previous/new state, ingestion source, source evidence and deduplication key.

## Correlation algorithm (initial proposal — tune against data)
- Ingest and deduplicate alarms; reconcile current state with periodic polling to recover missed traps.
- Use event time with a lateness allowance and source-clock validation.
- Initial rolling window: **90 seconds**; initial candidate threshold: **at least 3 newly affected eligible ONUs and >=75% of eligible ONUs in a candidate subtree**. These are *untested starting values*, not mandatory thresholds.
- Eligible denominator should use ONUs known online just before the event with fresh telemetry, excluding permanently offline/decommissioned or maintenance-mode ONUs.
- Evaluate candidate subtrees / fiber branches, not only PON-level aggregates. Calculate downstream affected/eligible/confirmed-healthy counts; rank smallest plausible shared fault domains that explain most failures while accounting for healthy neighbors. Identify splitter/input, cable and connector as alternatives within a domain; don't claim an exact break.
- Prioritize direct device/port alarms; distinguish ONU LOS vs Dying Gasp vs unknown vs equipment unreachable and ISP-side uplink failures.
- Merge overlapping candidates when they describe the same incident; avoid duplicate parent/child notifications; update and resolve after verified stable recovery.
- Keep explicit unknown status for stale collector/OLT communication; never turn loss of monitoring visibility into presumed mass fiber failure.
- Use configurable confidence/evidence indicators rather than an unsupported "100% confirmed fiber break" label.

### Example topology / expected output (fictional)
OLT-01 PON-03 -> F1 -> S1, branching via F2 -> S2 -> ONUs 01-08, and F3 -> S3 -> ONUs 09-16. If 8 ONUs on S2 report LOS within ~40s and all 8 on S3 remain healthy, alert one shared optical-path outage for F2/S2, not F1, with affected customer count 8 and explicit localization uncertainty.

### Must-test counterexamples
- One ONU disappears: probably individual drop/equipment, not an automatically declared branch break.
- All eight ONUs under S2 report power loss/Dying Gasp: prioritize local electricity explanation.
- The OLT's management connection fails: collector monitoring unknown, not sixteen optical outages.
- PON port down and many ONUs offline: equipment fault should supersede inferred cable-break hypotheses.
- Simultaneous unrelated failures across two branches; inaccurate or missing topology; flap/recovery; duplicate and out-of-order alarms.

## Development sequence
1. Create simulator, topology model and testable correlation/incident engine using fake data. No access to live equipment.
2. Inspect the Syrotech firmware/version and confirm supported read-only management interfaces/OIDs/event export on a separate authorized test.
3. Build/verify vendor adapter for ONU status, events and optical measurements; integrate read-only MikroTik monitoring later.
4. Add basic operational dashboard and incident history.
5. Add accurate GIS assets and interactive fault map once field data is reliable.
6. Harden authentication, alerts, retention, deployment and external heartbeat.

## Open questions
- Firmware/hardware revision of actual Syrotech OLT; SNMP version and supported ONU MIB/OIDs; supported traps/syslog; native disconnect-reason semantics.
- MikroTik model/RouterOS version and ownership of PPPoE/VLAN routing.
- Number of ONUs/customers; all PON port assignments; splitter topology/routes and accuracy.
- Poll intervals and collector load tolerances; operational staff/notification channels; network connectivity and server/UPS availability.
