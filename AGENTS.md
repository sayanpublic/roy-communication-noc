# Codex Instructions — Roy Communication NOC

Read `docs/PROJECT_CONTEXT.md`, `docs/DECISIONS.md`, and `docs/PROGRESS.md` before making architectural or implementation changes.

## Purpose
Develop a private Network Operations Center (NOC) for Roy Communication, a local broadband distributor in Katwa, West Bengal, India. It combines EPON ONU telemetry, router health, mapped fiber/splitter topology, and correlation of simultaneous customer outages. The product must be useful operationally, not just visually impressive.

## Engineering conventions
- Prefer Java 21 + Spring Boot as a modular monolith; frontend React + TypeScript + Leaflet; PostgreSQL/PostGIS when persistent GIS data is needed; Docker Compose for local services.
- Start with simulated events and an independently testable fault-correlation engine. Avoid premature Kafka, Kubernetes, and microservices. Consider Zabbix as an optional monitoring collector, not an immediate dependency.
- Keep vendor-specific code inside adapters, normalize event types, retain original event metadata, and handle stale, missing and out-of-order events.
- Distinguish observed device/ONU state from inferred cable fault domain. Never assert that a passive fiber segment is physically broken based on ONU state alone.
- Write focused automated tests for single-ONU loss, multiple shared-branch LOS events, whole-PON outage, Dying Gasp/power events, ONU recovery, event duplicates, telemetry loss, overlapping groups and topology errors.
- Before each milestone, briefly explain the plan, files affected, and acceptance tests. Run available tests, report actual results, and summarize changed files and remaining risks.
- At the end of each coding task, update `docs/PROGRESS.md`; record significant architecture decisions in `docs/DECISIONS.md`. Ask for missing hardware/firmware details rather than inventing OIDs or vendor commands.
- Make small, reviewable Git commits when requested; do not overwrite unrelated local changes.

## Safety and privacy boundaries
- NO direct configuration changes, reboots, firmware updates, destructive tests, fiber disconnections, or modifications to live routers/OLTs without explicit, task-specific owner approval and an agreed maintenance window.
- Do not connect to production network equipment during the initial simulator/correlation milestone.
- For later integrations, use authorized read-only accounts, least privilege and network access controls. Prefer SNMPv3 when supported. Never enable an exposed public management interface.
- Never hardcode, commit, print, or share passwords, SNMP communities, API keys, public/customer IPs, private phone numbers, or precise customer location data. Use environment variables and `.env.example` with placeholders only; `.env` must be Git-ignored.
- The dashboard should be private, authenticated, and access-controlled. Customer data should be minimized in logs and test fixtures.
