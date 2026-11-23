package in.roycommunication.noc.correlation;

import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** A deterministic, in-memory replay of one PON. advanceTo drives time even between alarms. */
public final class CorrelationEngine {
    public enum OnuState { ONLINE, LOS, DYING_GASP, OFFLINE_UNKNOWN, UNKNOWN }
    public enum PortState { REACHABLE, DOWN, UNKNOWN }
    public record OnuReading(OnuState state, Alarm lastObservation) {}
    public record Snapshot(Instant asOf, PortState portState, Map<String, OnuReading> onus,
                           List<Alarm> observations, List<Incident> incidents) {
        public Snapshot {
            onus = Map.copyOf(onus);
            observations = List.copyOf(observations);
            incidents = List.copyOf(incidents);
        }
    }

    // At a shared timestamp, apply visibility and port authority before downstream observations.
    private static final Comparator<Alarm> EVENT_ORDER = Comparator.comparing(Alarm::eventTimestamp)
            .thenComparingInt(alarm -> switch (alarm.type()) {
                case OLT_REACHABLE -> 0;
                case OLT_UNREACHABLE -> 1;
                case PON_PORT_UP -> 2;
                case PON_PORT_DOWN -> 3;
                case ONU_ONLINE -> 4;
                case ONU_LOS -> 5;
                case ONU_OFFLINE_UNKNOWN -> 6;
                case ONU_DYING_GASP -> 7;
            }).thenComparing(Alarm::eventId);

    private final FiberTopology topology;
    private final CorrelationPolicy policy;
    private final Map<String, Alarm> journal = new LinkedHashMap<>();
    private Instant now = Instant.MIN;
    private Snapshot cached;

    public CorrelationEngine(FiberTopology topology, CorrelationPolicy policy) {
        this.topology = Objects.requireNonNull(topology);
        this.policy = Objects.requireNonNull(policy);
    }

    public synchronized void accept(Alarm alarm) {
        if (!topology.ponPort().equals(alarm.ponPort())) throw new IllegalArgumentException("Wrong PON port");
        Alarm previous = journal.get(alarm.eventId());
        if (previous != null && (previous.type() != alarm.type()
                || !Objects.equals(previous.onuId(), alarm.onuId())
                || !previous.eventTimestamp().equals(alarm.eventTimestamp()))) {
            throw new IllegalArgumentException("Conflicting observations for " + alarm.eventId());
        }
        if (previous == null || alarm.receiveTimestamp().isBefore(previous.receiveTimestamp())) {
            journal.put(alarm.eventId(), alarm);
            cached = null;
        }
        if (alarm.receiveTimestamp().isAfter(now)) advanceTo(alarm.receiveTimestamp());
    }

    public synchronized void advanceTo(Instant time) {
        if (time.isBefore(now)) throw new IllegalArgumentException("Replay clock cannot move backwards");
        if (!time.equals(now)) cached = null;
        now = time;
    }

    public synchronized Snapshot snapshot() {
        if (cached != null) return cached;
        var replay = new IncidentReplay(topology, policy);
        List<Alarm> observations = journal.values().stream().sorted(EVENT_ORDER).toList();
        for (Alarm alarm : observations) {
            Duration lag = Duration.between(alarm.eventTimestamp(), alarm.receiveTimestamp());
            if (!lag.isNegative() && lag.compareTo(policy.maxDeliveryLag()) <= 0) replay.apply(alarm);
        }
        cached = replay.snapshot(now, observations);
        return cached;
    }
}
