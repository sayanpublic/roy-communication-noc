package in.roycommunication.noc.correlation;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static in.roycommunication.noc.correlation.CorrelationEngine.OnuState;
import static in.roycommunication.noc.correlation.CorrelationEngine.PortState;
import static in.roycommunication.noc.correlation.Incident.Basis;
import static in.roycommunication.noc.correlation.Incident.Status;

/** Projects an event-time ordered journal into incident histories. */
final class IncidentReplay {
    private final FiberTopology topology;
    private final CorrelationPolicy policy;
    private final PonTelemetry telemetry;
    private final List<Tracked> incidents = new ArrayList<>();
    private final Map<FiberTopology.Scope, Integer> openings = new HashMap<>();

    IncidentReplay(FiberTopology topology, CorrelationPolicy policy) {
        this.topology = topology;
        this.policy = policy;
        this.telemetry = new PonTelemetry(topology, policy);
    }

    void apply(Alarm alarm) {
        Instant at = alarm.eventTimestamp();
        // Alarms stamped at the recovery deadline participate in the stability decision.
        advanceTimers(at, false);
        boolean newlyLost = telemetry.observe(alarm);
        var evidence = new HashMap<Tracked, List<String>>();
        if (alarm.type() == Alarm.Type.PON_PORT_DOWN && telemetry.portDown()) {
            var candidate = telemetry.portEvidence(at);
            Tracked incident = associate(candidate, Basis.OBSERVED_PORT_DOWN, at);
            var ids = new ArrayList<>(evidenceIds(candidate));
            ids.add(alarm.eventId());
            evidence.put(incident, ids);
        } else if (newlyLost && !telemetry.portDown()) {
            for (var candidate : inferredScopes(at)) {
                Tracked incident = associate(candidate, Basis.INFERRED_SHARED_LOS, at);
                evidence.put(incident, evidenceIds(candidate));
            }
        }
        for (Tracked incident : incidents) {
            if (!incident.active()) continue;
            if (newlyLost && incident.eligible.contains(alarm.onuId())) incident.impacted.add(alarm.onuId());
            boolean recovered = incident.recovered(at);
            if (!recovered) incident.recoveringSince = null;
            else if (incident.recoveringSince == null) incident.recoveringSince = at;
            incident.record(at, recovered ? Status.RECOVERING : Status.OPEN,
                    evidence.getOrDefault(incident, List.of(alarm.eventId())));
        }
    }

    private static List<String> evidenceIds(PonTelemetry.Candidate candidate) {
        return candidate.losses().values().stream().map(Alarm::eventId).toList();
    }

    private List<PonTelemetry.Candidate> inferredScopes(Instant at) {
        List<PonTelemetry.Candidate> selected = new ArrayList<>();
        for (var candidate : telemetry.qualifyingDomains(at)) {
            Set<String> members = candidate.domain().onus();
            Set<String> losses = candidate.losses().keySet();
            if (topology.confinedToChild(candidate.domain(), losses)) continue;
            boolean unrelated = incidents.stream().filter(Tracked::active)
                    .filter(i -> !i.domain.equals(candidate.domain()) && members.containsAll(i.domain.onus()))
                    .anyMatch(i -> !i.explainedBy(losses, at));
            if (unrelated) continue;

            var children = selected.stream().filter(child -> members.containsAll(child.domain().onus())).toList();
            // Widen a localized cluster only when the whole eligible parent is affected across child paths.
            if (!children.isEmpty() && losses.size() < candidate.eligible().size()) continue;
            selected.removeAll(children);
            selected.add(candidate);
        }
        return selected;
    }

    private Tracked associate(PonTelemetry.Candidate candidate, Basis basis, Instant at) {
        var domain = candidate.domain();
        List<Tracked> overlapping = incidents.stream().filter(Tracked::active)
                .filter(i -> i.domain.onus().containsAll(domain.onus()) || domain.onus().containsAll(i.domain.onus()))
                .toList();
        Tracked owner;
        if (overlapping.isEmpty()) {
            var scope = domain.scope();
            int episode = openings.merge(scope, 1, Integer::sum);
            String path = scope.kind() == FiberTopology.Kind.PON ? topology.ponPort() : topology.ponPort() + "/" + scope.id();
            owner = new Tracked(path + "/" + episode, domain, basis);
            owner.eligible.addAll(candidate.eligible());
            incidents.add(owner);
        } else {
            // The first open incident owns the broader domain. Keep a link from each absorbed history.
            owner = overlapping.getFirst();
            if (!owner.domain.equals(domain) && domain.onus().containsAll(owner.domain.onus())) {
                owner.domain = domain;
                owner.eligible.addAll(candidate.eligible());
            }
            if (basis == Basis.OBSERVED_PORT_DOWN) owner.basis = basis;
            for (Tracked other : overlapping.subList(1, overlapping.size())) {
                owner.eligible.addAll(other.eligible);
                owner.impacted.addAll(other.impacted);
                other.supersededBy = owner.id;
                other.record(at, Status.SUPERSEDED, evidenceIds(candidate));
            }
        }
        for (String id : candidate.losses().keySet()) {
            if (owner.eligible.contains(id)) owner.impacted.add(id);
        }
        return owner;
    }

    private void advanceTimers(Instant at, boolean includeDeadline) {
        for (Tracked incident : incidents) {
            if (!incident.active() || incident.recoveringSince == null) continue;
            Instant deadline = incident.recoveringSince.plus(policy.stableRecovery());
            Instant check = deadline.isAfter(at) ? at : deadline;
            if (!incident.recovered(check)) {
                incident.recoveringSince = null;
                incident.record(check, Status.OPEN, List.of());
            } else if (deadline.isBefore(at) || (includeDeadline && deadline.equals(at))) {
                incident.record(deadline, Status.RESOLVED, List.of());
            }
        }
    }

    CorrelationEngine.Snapshot snapshot(Instant at, List<Alarm> observations) {
        advanceTimers(at, true);
        return new CorrelationEngine.Snapshot(at, telemetry.portState(at), telemetry.readings(at), observations,
                incidents.stream().map(i -> new Incident(i.id, i.timeline, i.supersededBy)).toList());
    }

    private final class Tracked {
        final String id;
        final List<Incident.Change> timeline = new ArrayList<>();
        final Set<String> eligible = new LinkedHashSet<>();
        final Set<String> impacted = new LinkedHashSet<>();
        FiberTopology.Domain domain;
        Basis basis;
        Instant recoveringSince;
        String supersededBy;

        Tracked(String id, FiberTopology.Domain domain, Basis basis) {
            this.id = id;
            this.domain = domain;
            this.basis = basis;
        }

        boolean active() {
            return timeline.isEmpty() || switch (timeline.getLast().status()) {
                case OPEN, RECOVERING -> true;
                case RESOLVED, SUPERSEDED -> false;
            };
        }

        boolean recovered(Instant at) {
            return telemetry.portState(at) == PortState.REACHABLE
                    && (basis != Basis.OBSERVED_PORT_DOWN || telemetry.portConfirmedUp(at))
                    && impacted.stream().allMatch(id -> telemetry.state(id, at) == OnuState.ONLINE);
        }

        boolean explainedBy(Set<String> losses, Instant at) {
            return impacted.stream().anyMatch(losses::contains)
                    && impacted.stream().allMatch(id -> losses.contains(id) || telemetry.state(id, at) == OnuState.ONLINE);
        }

        void record(Instant at, Status status, List<String> evidence) {
            Set<String> affected = new LinkedHashSet<>();
            for (String onu : impacted) {
                if (telemetry.state(onu, at) == OnuState.LOS) affected.add(onu);
            }
            if (!timeline.isEmpty()) {
                var previous = timeline.getLast();
                if (previous.status() == status && previous.basis() == basis && previous.scope().equals(domain.scope())
                        && previous.affected().equals(affected) && previous.eligible().equals(eligible)) return;
            }
            timeline.add(new Incident.Change(at, domain.scope(), basis, status, affected, eligible, evidence));
        }
    }
}
