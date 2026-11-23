package in.roycommunication.noc.correlation;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static in.roycommunication.noc.correlation.Alarm.Type.*;
import static in.roycommunication.noc.correlation.CorrelationEngine.OnuState;
import static in.roycommunication.noc.correlation.FiberTopology.*;
import static in.roycommunication.noc.correlation.ReplayFixture.*;
import static org.junit.jupiter.api.Assertions.*;

class FiberTopologyTest {
    @Test
    void belowThresholdChildSplittersCanJointlySupportTheirSharedParent() {
        var splitters = List.of(new Splitter("S1", PON, "F1"), new Splitter("S2", "S1", "F2"),
                new Splitter("S3", "S1", "F3"), new Splitter("S4", "S2", "F4"), new Splitter("S5", "S2", "F5"));
        var onus = new ArrayList<Onu>();
        for (int i = 1; i <= 8; i++) {
            String parent = i <= 2 ? "S4" : i <= 4 ? "S5" : "S3";
            onus.add(new Onu(onu(i), parent, Service.ACTIVE));
        }
        var topology = new FiberTopology(PON, splitters, onus);
        assertTrue(topology.problems().isEmpty());
        var engine = new CorrelationEngine(topology, CorrelationPolicy.defaults());
        online(engine, 8, 0);
        for (int i = 1; i <= 3; i++) report(engine, ONU_LOS, i, i);
        var opened = only(active(engine)).latest();
        assertEquals(new Scope(Kind.SPLITTER, "S2", "F2"), opened.scope());
        assertEquals(3, opened.affected().size());
        assertEquals(4, opened.eligible().size());
    }

    @Test
    void absentTopologyAndUnmappedOnusStillExposeTheirObservations() {
        for (var topology : List.of(new FiberTopology(PON, List.of(), List.of()), tree(2, 2))) {
            var engine = new CorrelationEngine(topology, CorrelationPolicy.defaults());
            for (int i = 20; i <= 25; i++) {
                report(engine, ONU_ONLINE, i, 0);
                report(engine, ONU_LOS, i, 10);
            }
            assertTrue(engine.snapshot().incidents().isEmpty());
            assertEquals(12, engine.snapshot().observations().size());
            assertEquals(OnuState.LOS, engine.snapshot().onus().get(onu(25)).state());
        }
    }

    @Test
    void orphanCycleAndDuplicateAttachmentDisableInferenceButPreservePortEvidence() {
        var good = List.of(new Splitter("S1", PON, "F1"));
        var members = List.of(new Onu(onu(1), "S1", Service.ACTIVE), new Onu(onu(2), "S1", Service.ACTIVE),
                new Onu(onu(3), "S1", Service.ACTIVE), new Onu(onu(4), "S1", Service.ACTIVE));
        var duplicated = new ArrayList<>(members);
        duplicated.add(new Onu(onu(3), PON, Service.ACTIVE));
        var orphan = new ArrayList<>(members);
        orphan.add(new Onu(onu(5), "absent-splitter", Service.ACTIVE));
        var cycle = List.of(new Splitter("S1", "S2", "F1"), new Splitter("S2", "S1", "F2"));
        for (var topology : List.of(new FiberTopology(PON, good, duplicated),
                new FiberTopology(PON, good, orphan), new FiberTopology(PON, cycle, members))) {
            assertFalse(topology.problems().isEmpty());
            var engine = new CorrelationEngine(topology, CorrelationPolicy.defaults());
            online(engine, 4, 0);
            for (int i = 1; i <= 4; i++) report(engine, ONU_LOS, i, i);
            assertTrue(engine.snapshot().incidents().isEmpty());
            assertEquals(OnuState.LOS, engine.snapshot().onus().get(onu(1)).state());
            engine.accept(alarm(PON_PORT_DOWN, null, 10));
            assertEquals(Incident.Basis.OBSERVED_PORT_DOWN, only(active(engine)).latest().basis());
        }
    }
}
