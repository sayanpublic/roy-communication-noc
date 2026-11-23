package in.roycommunication.noc.correlation;

import in.roycommunication.noc.simulator.OltSimulator;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static in.roycommunication.noc.correlation.Alarm.Type;
import static in.roycommunication.noc.correlation.FiberTopology.*;
import static org.junit.jupiter.api.Assertions.assertEquals;

final class ReplayFixture {
    static final String PON = "PON-03";
    static final Instant START = Instant.parse("2026-01-01T00:00:00Z");

    static FiberTopology tree(int... branchSizes) { return tree(Map.of(), branchSizes); }

    static FiberTopology tree(Map<Integer, Service> service, int... branchSizes) {
        var splitters = new ArrayList<>(List.of(new Splitter("S1", PON, "F1")));
        var onus = new ArrayList<Onu>();
        int number = 1;
        for (int branch = 0; branch < branchSizes.length; branch++) {
            String splitter = "S" + (branch + 2);
            splitters.add(new Splitter(splitter, "S1", "F" + (branch + 2)));
            for (int i = 0; i < branchSizes[branch]; i++, number++) {
                onus.add(new Onu(onu(number), splitter, service.getOrDefault(number, Service.ACTIVE)));
            }
        }
        return new FiberTopology(PON, splitters, onus);
    }

    static CorrelationEngine engine() {
        return new CorrelationEngine(tree(8, 8), CorrelationPolicy.defaults());
    }

    static Alarm normalize(OltSimulator.Event e) {
        return new Alarm(e.eventId(), e.ponPort(), e.onuId(), Type.valueOf(e.eventType().name()),
                e.eventTimestamp(), e.receiveTimestamp());
    }

    static String onu(int number) { return String.format(Locale.ROOT, "ONU-%02d", number); }

    static Alarm alarm(Type type, String onu, long second) {
        Instant time = START.plusSeconds(second);
        return new Alarm(type + "/" + onu + "/" + second, PON, onu, type, time, time.plusMillis(250));
    }

    static void report(CorrelationEngine engine, Type type, int number, long second) {
        engine.accept(alarm(type, onu(number), second));
    }

    static void online(CorrelationEngine engine, int count, long second) {
        for (int i = 1; i <= count; i++) report(engine, Type.ONU_ONLINE, i, second);
    }

    static List<Incident> active(CorrelationEngine engine) {
        return engine.snapshot().incidents().stream().filter(Incident::active).toList();
    }

    static Incident only(List<Incident> incidents) {
        assertEquals(1, incidents.size());
        return incidents.getFirst();
    }
}
