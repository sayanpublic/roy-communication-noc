package in.roycommunication.noc.correlation;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static in.roycommunication.noc.correlation.Alarm.Type.*;
import static in.roycommunication.noc.correlation.CorrelationEngine.OnuState;
import static in.roycommunication.noc.correlation.CorrelationEngine.PortState;

/** State derived only from observations, plus pre-outage cohorts and recent LOS evidence. */
final class PonTelemetry {
    record Candidate(FiberTopology.Domain domain, Set<String> eligible, Map<String, Alarm> losses) {}

    private final FiberTopology topology;
    private final CorrelationPolicy policy;
    private final Map<String, Alarm> lastObserved = new LinkedHashMap<>();
    private final Map<String, Alarm> visible = new HashMap<>();
    private final Map<String, Alarm> lossOnsets = new LinkedHashMap<>();
    private final Map<String, String> onsetByRepeat = new HashMap<>();
    private final Map<FiberTopology.Domain, Set<String>> cohorts = new HashMap<>();
    private boolean reachable = true;
    private Instant lastContact;
    private Alarm portAlarm;

    PonTelemetry(FiberTopology topology, CorrelationPolicy policy) {
        this.topology = topology;
        this.policy = policy;
    }

    boolean observe(Alarm alarm) {
        Instant at = alarm.eventTimestamp();
        if (alarm.onuId() != null) lastObserved.put(alarm.onuId(), alarm);
        if (alarm.type() == OLT_UNREACHABLE) {
            reachable = false;
            visible.clear();
            lossOnsets.clear();
            cohorts.clear();
            portAlarm = null;
            return false;
        }
        if (alarm.type() == OLT_REACHABLE) reachable = true;
        if (!reachable) return false;
        lastContact = at;
        if (alarm.type() == PON_PORT_DOWN || alarm.type() == PON_PORT_UP) portAlarm = alarm;
        if (alarm.onuId() == null) return false;

        String id = alarm.onuId();
        Alarm previous = visible.get(id);
        boolean newlyLost = alarm.type() == ONU_LOS && topology.inService(id)
                && previous != null && previous.type() == ONU_ONLINE;
        if (newlyLost) {
            for (var domain : topology.domains()) {
                if (domain.onus().contains(id) && recentLosses(domain, at).isEmpty()) {
                    // Freeze the denominator before replacing this ONU's fresh ONLINE observation.
                    cohorts.put(domain, onlineMembers(domain, at));
                }
            }
            lossOnsets.put(id, alarm);
        } else if (alarm.type() == ONU_LOS) {
            Alarm onset = lossOnsets.get(id);
            if (onset != null) onsetByRepeat.put(alarm.eventId(), onset.eventId());
        } else {
            lossOnsets.remove(id);
        }
        visible.put(id, alarm);
        return newlyLost;
    }

    // A late onset can turn a previously published LOS into a repeat of the same continuous loss.
    String causalAlarmId(String eventId) { return onsetByRepeat.getOrDefault(eventId, eventId); }

    private boolean within(Instant since, Instant at, Duration limit) {
        return since != null && Duration.between(since, at).compareTo(limit) <= 0;
    }

    OnuState state(String id, Instant at) {
        Alarm last = visible.get(id);
        if (!reachable || last == null || !within(last.eventTimestamp(), at, policy.freshness())) return OnuState.UNKNOWN;
        return switch (last.type()) {
            case ONU_ONLINE -> OnuState.ONLINE;
            case ONU_LOS -> OnuState.LOS;
            case ONU_DYING_GASP -> OnuState.DYING_GASP;
            case ONU_OFFLINE_UNKNOWN -> OnuState.OFFLINE_UNKNOWN;
            default -> throw new IllegalStateException("Expected ONU observation");
        };
    }

    PortState portState(Instant at) {
        if (!reachable || !within(lastContact, at, policy.freshness())) return PortState.UNKNOWN;
        if (portDown()) return within(portAlarm.eventTimestamp(), at, policy.freshness()) ? PortState.DOWN : PortState.UNKNOWN;
        return PortState.REACHABLE;
    }

    boolean portDown() { return portAlarm != null && portAlarm.type() == PON_PORT_DOWN; }
    boolean portConfirmedUp(Instant at) {
        return portAlarm != null && portAlarm.type() == PON_PORT_UP && within(portAlarm.eventTimestamp(), at, policy.freshness());
    }

    Map<String, CorrelationEngine.OnuReading> readings(Instant at) {
        Set<String> ids = new HashSet<>(topology.port().onus());
        ids.addAll(lastObserved.keySet());
        var result = new LinkedHashMap<String, CorrelationEngine.OnuReading>();
        for (String id : ids) result.put(id, new CorrelationEngine.OnuReading(state(id, at), lastObserved.get(id)));
        return result;
    }

    private Set<String> onlineMembers(FiberTopology.Domain domain, Instant at) {
        Set<String> result = new HashSet<>();
        for (String id : domain.onus()) {
            if (topology.inService(id) && state(id, at) == OnuState.ONLINE) result.add(id);
        }
        return Set.copyOf(result);
    }

    private Map<String, Alarm> recentLosses(FiberTopology.Domain domain, Instant at) {
        Set<String> eligible = cohorts.getOrDefault(domain, Set.of());
        var result = new LinkedHashMap<String, Alarm>();
        lossOnsets.forEach((id, onset) -> {
            if (eligible.contains(id) && within(onset.eventTimestamp(), at, policy.window())
                    && state(id, at) == OnuState.LOS) result.put(id, onset);
        });
        return result;
    }

    Candidate portEvidence(Instant at) {
        var port = topology.port();
        var losses = recentLosses(port, at);
        Set<String> eligible = losses.isEmpty() ? onlineMembers(port, at) : cohorts.getOrDefault(port, Set.of());
        return new Candidate(port, eligible, losses);
    }

    List<Candidate> qualifyingDomains(Instant at) {
        List<Candidate> candidates = new ArrayList<>();
        for (var domain : topology.domains()) {
            Set<String> eligible = cohorts.getOrDefault(domain, Set.of());
            Map<String, Alarm> losses = recentLosses(domain, at);
            if (policy.qualifies(losses.size(), eligible.size())) candidates.add(new Candidate(domain, eligible, losses));
        }
        return candidates;
    }
}
