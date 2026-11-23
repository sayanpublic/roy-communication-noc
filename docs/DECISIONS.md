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
