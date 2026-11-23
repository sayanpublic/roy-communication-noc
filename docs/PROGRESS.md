# Development Progress — Roy Communication NOC

## Current status
**Planning / not yet implemented** (starter context prepared from ChatGPT discussion).

## Next milestone
Build a fully offline vertical slice with:
- In-memory optical topology including cascaded splitter branches.
- Synthetic EPON OLT event generator.
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

## To update after each Codex task
Record date, branch/commit, files changed, tested commands and results, decisions, known problems and the next proposed development task. Never place secrets or customer PII in this file.
