package in.roycommunication.noc.api;

import in.roycommunication.noc.adapter.syrotech.OnuStatusSnapshot;
import in.roycommunication.noc.adapter.syrotech.OnuStatusSnapshot.LastDownReason;
import in.roycommunication.noc.adapter.syrotech.SyrotechEponAdapter;
import in.roycommunication.noc.correlation.Alarm;
import in.roycommunication.noc.correlation.CorrelationEngine;
import in.roycommunication.noc.correlation.CorrelationPolicy;
import in.roycommunication.noc.correlation.FiberTopology;
import in.roycommunication.noc.correlation.FiberTopology.Onu;
import in.roycommunication.noc.correlation.FiberTopology.Splitter;
import in.roycommunication.noc.simulator.OltSimulator;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.TreeMap;

import static in.roycommunication.noc.adapter.syrotech.OnuStatusSnapshot.Status.OFFLINE;
import static in.roycommunication.noc.adapter.syrotech.OnuStatusSnapshot.Status.ONLINE;
import static in.roycommunication.noc.correlation.FiberTopology.Service.ACTIVE;
import static in.roycommunication.noc.simulator.OltSimulator.PON_PORT;

final class SyntheticReplays {
    private static final Instant START = Instant.parse("2026-01-01T00:00:00Z");
    private static final Instant S2_OPEN = START.plusSeconds(55).plusMillis(250);
    private static final CorrelationPolicy POLICY = CorrelationPolicy.defaults();

    private record Network(FiberTopology engineTopology, ReplayView.Topology displayTopology) {}

    private SyntheticReplays() {}

    static List<ReplayView> load() {
        var standard = topology(false, OltSimulator.S2_ONUS, OltSimulator.S3_ONUS);
        var cascade = topology(true, OltSimulator.S2_ONUS, OltSimulator.S3_ONUS);
        var adapterNetwork = topology(false, OltSimulator.S2_ONUS.subList(0, 4), List.of("ONU-09"));
        var branchLos = normalize(OltSimulator.branchLos());
        var dyingGasp = normalize(OltSimulator.dyingGasp());
        var delayed = delayedLos(adapterNetwork);
        Instant resolvedAt = branchLos.getLast().eventTimestamp().plus(POLICY.stableRecovery());
        return List.of(
                viewAt("s2-open", standard, branchLos, S2_OPEN),
                viewAt("s2-resolved", standard, branchLos, resolvedAt),
                viewAt("dying-gasp", standard, dyingGasp, dyingGasp.getLast().receiveTimestamp()),
                viewAt("cascade-open", cascade, branchLos, S2_OPEN),
                viewAt("delayed-los-pending", adapterNetwork, delayed, START.plusSeconds(15).plusMillis(250)),
                viewAt("delayed-los-confirmed", adapterNetwork, delayed, START.plusSeconds(24).plusMillis(250)));
    }

    private static List<Alarm> normalize(List<OltSimulator.Event> events) {
        return events.stream().map(event -> new Alarm(event.eventId(), event.ponPort(), event.onuId(),
                Alarm.Type.valueOf(event.eventType().name()), event.eventTimestamp(), event.receiveTimestamp())).toList();
    }

    private static List<Alarm> delayedLos(Network network) {
        var rows = new ArrayList<OnuStatusSnapshot>();
        for (String id : network.displayTopology().onuParents().keySet()) {
            rows.add(row(id, 0, ONLINE, null));
        }
        for (int i = 0; i < 3; i++) {
            String id = OltSimulator.S2_ONUS.get(i);
            rows.add(row(id, 10 + i, OFFLINE, null));
            rows.add(row(id, 15, OFFLINE, null));
            rows.add(row(id, 20 + 2 * i, OFFLINE, new LastDownReason("LOS", START.plusSeconds(10 + i))));
        }
        return new SyrotechEponAdapter(Duration.ofSeconds(30)).normalize(rows).stream()
                .map(SyrotechEponAdapter.Observation::alarm).toList();
    }

    private static OnuStatusSnapshot row(String id, int second, OnuStatusSnapshot.Status status, LastDownReason reason) {
        var observed = START.plusSeconds(second);
        return new OnuStatusSnapshot("delayed-" + id + "-" + second, PON_PORT, id, status,
                observed, observed.plusMillis(250), reason);
    }

    private static ReplayView viewAt(String id, Network network, List<Alarm> events, Instant at) {
        var engine = new CorrelationEngine(network.engineTopology(), POLICY);
        for (var event : events) {
            if (!event.receiveTimestamp().isAfter(at)) engine.accept(event);
        }
        engine.advanceTo(at);
        return ReplayView.from(id, network.engineTopology().ponPort(), engine.snapshot(), network.displayTopology());
    }

    private static Network topology(boolean cascaded, List<String> s2, List<String> s3) {
        var splitters = new ArrayList<>(List.of(new Splitter("S1", PON_PORT, "F1"),
                new Splitter("S2", "S1", "F2"), new Splitter("S3", "S1", "F3")));
        if (cascaded) splitters.add(new Splitter("S4", "S2", "F4"));
        String affectedSplitter = cascaded ? "S4" : "S2";
        var parents = new TreeMap<String, String>();
        for (String id : s2) parents.put(id, affectedSplitter);
        for (String id : s3) parents.put(id, "S3");
        var onus = parents.entrySet().stream().map(entry -> new Onu(entry.getKey(), entry.getValue(), ACTIVE)).toList();
        return new Network(new FiberTopology(PON_PORT, splitters, onus),
                new ReplayView.Topology(List.copyOf(splitters), Collections.unmodifiableSortedMap(parents)));
    }
}
