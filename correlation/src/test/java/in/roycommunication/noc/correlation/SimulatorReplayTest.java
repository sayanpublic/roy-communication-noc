package in.roycommunication.noc.correlation;

import in.roycommunication.noc.simulator.OltSimulator;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static in.roycommunication.noc.correlation.CorrelationEngine.OnuState;
import static in.roycommunication.noc.correlation.Incident.*;
import static in.roycommunication.noc.correlation.ReplayFixture.*;
import static org.junit.jupiter.api.Assertions.*;

class SimulatorReplayTest {
    @Test
    void sixthLossOpensS2AndStableRecoveryResolvesTheSameIncident() {
        var engine = engine();
        List<Alarm> replay = OltSimulator.branchLos().stream().map(ReplayFixture::normalize).toList();
        replay.subList(0, 21).forEach(engine::accept);
        assertTrue(engine.snapshot().incidents().isEmpty());

        engine.accept(replay.get(21));
        Incident first = only(engine.snapshot().incidents());
        var opened = first.latest();
        assertEquals(new FiberTopology.Scope(FiberTopology.Kind.SPLITTER, "S2", "F2"), opened.scope());
        assertEquals(Basis.INFERRED_SHARED_LOS, opened.basis());
        assertEquals(Status.OPEN, opened.status());
        assertEquals(START.plusSeconds(55), opened.at());
        assertEquals(Set.of(onu(1), onu(2), onu(3), onu(4), onu(5), onu(6)), opened.affected());
        assertEquals(Set.of(onu(1), onu(2), onu(3), onu(4), onu(5), onu(6), onu(7), onu(8)), opened.eligible());
        assertEquals(replay.subList(16, 22).stream().map(Alarm::eventId).toList(), opened.evidenceIds());

        engine.accept(replay.get(22));
        assertEquals(7, only(active(engine)).latest().affected().size());
        engine.accept(replay.get(23));
        Incident peak = only(engine.snapshot().incidents());
        assertEquals(first.id(), peak.id());
        assertEquals(List.of(6, 7, 8), peak.timeline().stream().map(c -> c.affected().size()).toList());
        assertEquals(START.plusSeconds(65), peak.latest().at());
        for (int i = 9; i <= 16; i++) assertEquals(OnuState.ONLINE, engine.snapshot().onus().get(onu(i)).state());

        replay.subList(24, 31).forEach(engine::accept);
        assertEquals(Status.OPEN, only(active(engine)).latest().status());
        engine.accept(replay.getLast());
        var recovering = only(active(engine)).latest();
        assertEquals(Status.RECOVERING, recovering.status());
        assertEquals(START.plusSeconds(127), recovering.at());
        engine.advanceTo(START.plusSeconds(157).minusNanos(1));
        assertEquals(Status.RECOVERING, only(active(engine)).latest().status());
        engine.advanceTo(START.plusSeconds(157));
        Incident done = only(engine.snapshot().incidents());
        assertEquals(first.id(), done.id());
        assertEquals(Status.RESOLVED, done.latest().status());
        assertEquals(START.plusSeconds(157), done.latest().at());
        assertTrue(done.latest().affected().isEmpty());
        assertEquals(opened.eligible(), done.latest().eligible());
        System.out.printf("S2 replay %s opened %d/%d at %s, reached %d/%d at %s, recovered at %s, resolved at %s%n",
                done.id(), opened.affected().size(), opened.eligible().size(), opened.at(),
                peak.latest().affected().size(), peak.latest().eligible().size(), peak.latest().at(),
                recovering.at(), done.latest().at());
    }

    @Test
    void dyingGaspChangesOnlyThePowerAffectedOnu() {
        var engine = engine();
        OltSimulator.dyingGasp().stream().map(ReplayFixture::normalize).forEach(engine::accept);
        var snapshot = engine.snapshot();
        assertTrue(snapshot.incidents().isEmpty());
        assertEquals(OnuState.DYING_GASP, snapshot.onus().get("ONU-09").state());
        assertEquals(Alarm.Type.ONU_DYING_GASP, snapshot.onus().get("ONU-09").lastObservation().type());
        assertEquals(15, snapshot.onus().values().stream().filter(o -> o.state() == OnuState.ONLINE).count());
    }
}
