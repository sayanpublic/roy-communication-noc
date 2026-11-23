package in.roycommunication.noc.api;

import in.roycommunication.noc.correlation.Alarm;
import in.roycommunication.noc.correlation.CorrelationEngine;
import in.roycommunication.noc.correlation.CorrelationPolicy;
import in.roycommunication.noc.correlation.FiberTopology;
import in.roycommunication.noc.correlation.FiberTopology.Onu;
import in.roycommunication.noc.correlation.FiberTopology.Splitter;
import in.roycommunication.noc.simulator.OltSimulator;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static in.roycommunication.noc.correlation.FiberTopology.Service.ACTIVE;
import static in.roycommunication.noc.simulator.OltSimulator.PON_PORT;

final class SyntheticReplays {
    // The sixth LOS reaches the collector 250 ms after its device timestamp.
    private static final Instant S2_OPEN = Instant.parse("2026-01-01T00:00:55.250Z");
    private static final CorrelationPolicy POLICY = CorrelationPolicy.defaults();

    private SyntheticReplays() {}

    static List<ReplayView> load() {
        var standard = topology(false);
        var branchLos = OltSimulator.branchLos();
        var dyingGasp = OltSimulator.dyingGasp();
        Instant resolvedAt = branchLos.getLast().eventTimestamp().plus(POLICY.stableRecovery());
        return List.of(
                viewAt("s2-open", standard, branchLos, S2_OPEN),
                viewAt("s2-resolved", standard, branchLos, resolvedAt),
                viewAt("dying-gasp", standard, dyingGasp, dyingGasp.getLast().receiveTimestamp()),
                viewAt("cascade-open", topology(true), branchLos, S2_OPEN));
    }

    private static ReplayView viewAt(String id, FiberTopology topology, List<OltSimulator.Event> events, Instant at) {
        var engine = new CorrelationEngine(topology, POLICY);
        for (var event : events) {
            if (event.receiveTimestamp().isAfter(at)) continue;
            engine.accept(new Alarm(event.eventId(), event.ponPort(), event.onuId(),
                    Alarm.Type.valueOf(event.eventType().name()), event.eventTimestamp(), event.receiveTimestamp()));
        }
        engine.advanceTo(at);
        return ReplayView.from(id, topology.ponPort(), engine.snapshot());
    }

    private static FiberTopology topology(boolean cascaded) {
        var splitters = new ArrayList<>(List.of(new Splitter("S1", PON_PORT, "F1"),
                new Splitter("S2", "S1", "F2"), new Splitter("S3", "S1", "F3")));
        if (cascaded) splitters.add(new Splitter("S4", "S2", "F4"));
        String affectedSplitter = cascaded ? "S4" : "S2";
        var onus = new ArrayList<Onu>();
        for (String id : OltSimulator.S2_ONUS) onus.add(new Onu(id, affectedSplitter, ACTIVE));
        for (String id : OltSimulator.S3_ONUS) onus.add(new Onu(id, "S3", ACTIVE));
        return new FiberTopology(PON_PORT, splitters, onus);
    }
}
