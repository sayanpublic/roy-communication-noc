package in.roycommunication.noc.correlation;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
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
    private final Map<String, Tracked> ownerByAlarm = new HashMap<>();

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
            ownerByAlarm.put(alarm.eventId(), incident);
            var ids = new ArrayList<>(evidenceIds(candidate));
            ids.addFirst(alarm.eventId());
            evidence.put(incident, ids);
        } else if (newlyLost && !telemetry.portDown()) {
            for (var candidate : inferredScopes(at)) {
                Tracked incident = associate(candidate, Basis.INFERRED_SHARED_LOS, at);
                evidence.put(incident, evidenceIds(candidate));
            }
        }
        for (Tracked incident : incidents) {
            if (!incident.active()) continue;
            if (newlyLost) incident.addLoss(alarm);
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
            owner = new Tracked(domain, basis);
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
                other.supersededBy = owner;
                other.record(at, Status.SUPERSEDED, evidenceIds(candidate));
            }
        }
        candidate.losses().values().forEach(owner::addLoss);
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

    CorrelationEngine.Snapshot snapshot(Instant at, List<Alarm> observations, PublishedIds publishedIds) {
        advanceTimers(at, true);
        publishedIds.assign(this);
        return new CorrelationEngine.Snapshot(at, telemetry.portState(at), telemetry.readings(at), observations,
                incidents.stream().map(Tracked::snapshot).toList());
    }

    private Tracked matchOpeningAlarms(List<String> openingAlarms) {
        var counts = new HashMap<Tracked, Integer>();
        Tracked bestMatch = null;
        int highestCount = 0;
        for (String eventId : openingAlarms) {
            Tracked episode = ownerByAlarm.get(telemetry.causalAlarmId(eventId));
            if (episode == null) continue;
            int count = counts.merge(episode, 1, Integer::sum);
            if (count > highestCount) {
                highestCount = count;
                bestMatch = episode;
            } else if (count == highestCount) {
                bestMatch = null;
            }
        }
        return bestMatch;
    }

    /** Lives with the engine, across projections. A public number belongs to its original alarm evidence. */
    static final class PublishedIds {
        private final Map<String, List<String>> openingEvidenceById = new LinkedHashMap<>();
        private final Map<String, Integer> lastNumberByPath = new HashMap<>();

        private void assign(IncidentReplay replay) {
            // Reconnect existing links before allocating any numbers to newly discovered history.
            for (var binding : openingEvidenceById.entrySet()) {
                Tracked owner = replay.matchOpeningAlarms(binding.getValue());
                if (owner != null && owner.id == null) owner.id = binding.getKey();
            }
            for (Tracked incident : replay.incidents) {
                if (incident.id != null) continue;
                Incident.Change opening = incident.timeline.getFirst();
                String path = replay.topology.ponPort();
                if (opening.scope().kind() == FiberTopology.Kind.SPLITTER) path += "/" + opening.scope().id();
                incident.id = path + "/" + lastNumberByPath.merge(path, 1, Integer::sum);
                openingEvidenceById.put(incident.id, opening.evidenceIds());
            }
        }
    }

    private final class Tracked {
        String id;
        final List<Incident.Change> timeline = new ArrayList<>();
        final Set<String> eligible = new LinkedHashSet<>();
        final Set<String> impacted = new LinkedHashSet<>();
        FiberTopology.Domain domain;
        Basis basis;
        Instant recoveringSince;
        Tracked supersededBy;

        Tracked(FiberTopology.Domain domain, Basis basis) {
            this.domain = domain;
            this.basis = basis;
        }

        Incident snapshot() {
            return new Incident(id, timeline, supersededBy == null ? null : supersededBy.id);
        }

        void addLoss(Alarm loss) {
            if (eligible.contains(loss.onuId())) {
                impacted.add(loss.onuId());
                ownerByAlarm.putIfAbsent(loss.eventId(), this);
            }
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
            var scope = basis == Basis.OBSERVED_PORT_DOWN ? topology.observedPort() : domain.scope();
            Set<String> affected = new LinkedHashSet<>();
            for (String onu : impacted) {
                if (telemetry.state(onu, at) == OnuState.LOS) affected.add(onu);
            }
            if (!timeline.isEmpty()) {
                var previous = timeline.getLast();
                if (previous.status() == status && previous.basis() == basis && previous.scope().equals(scope)
                        && previous.affected().equals(affected) && previous.eligible().equals(eligible)) return;
            }
            timeline.add(new Incident.Change(at, scope, basis, status, affected, eligible, evidence));
        }
    }
}
