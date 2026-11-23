package in.roycommunication.noc.correlation;

import in.roycommunication.noc.simulator.OltSimulator;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static in.roycommunication.noc.correlation.Alarm.Type.*;
import static in.roycommunication.noc.correlation.CorrelationEngine.*;
import static in.roycommunication.noc.correlation.FiberTopology.*;
import static in.roycommunication.noc.correlation.Incident.*;
import static in.roycommunication.noc.correlation.ReplayFixture.*;
import static org.junit.jupiter.api.Assertions.*;

class CorrelationEngineTest {
    @Test
    void oneOnuCanReportLosRepeatedlyWithoutOpeningAnIncident() {
        var engine = engine();
        online(engine, 16, 0);
        for (int second = 1; second <= 8; second++) report(engine, ONU_LOS, 1, second);
        assertEquals(OnuState.LOS, engine.snapshot().onus().get(onu(1)).state());
        assertTrue(engine.snapshot().incidents().isEmpty());
    }

    @Test
    void wholePonLossConsolidatesOverlappingBranchEvidence() {
        var engine = engine();
        online(engine, 16, 0);
        for (int i = 1; i <= 8; i++) {
            report(engine, ONU_LOS, i, 30 + 2 * i);
            report(engine, ONU_LOS, i + 8, 31 + 2 * i);
        }
        Incident port = only(active(engine));
        assertEquals(Kind.PON, port.latest().scope().kind());
        assertEquals(PON, port.latest().scope().id());
        assertEquals(Basis.INFERRED_SHARED_LOS, port.latest().basis());
        assertEquals(16, port.latest().affected().size());
        assertEquals(16, port.latest().eligible().size());
        assertEquals(PortState.REACHABLE, engine.snapshot().portState());
        for (Incident prior : engine.snapshot().incidents()) {
            if (!prior.active()) {
                assertEquals(Status.SUPERSEDED, prior.latest().status());
                assertEquals(port.id(), prior.supersededBy());
            }
        }
        engine.accept(alarm(PON_PORT_DOWN, null, 60));
        Incident observed = only(active(engine));
        assertEquals(port.id(), observed.id());
        assertEquals(Basis.OBSERVED_PORT_DOWN, observed.latest().basis());
    }

    @Test
    void directPortAlarmTakesOverAnInferredScopeAndRequiresPortUpForRecovery() {
        var engine = engine();
        OltSimulator.branchLos().subList(0, 24).stream().map(ReplayFixture::normalize).forEach(engine::accept);
        Incident branch = only(active(engine));
        Alarm down = alarm(PON_PORT_DOWN, null, 70);
        engine.accept(down);
        Incident port = only(active(engine));
        assertEquals(branch.id(), port.id());
        assertEquals(PON, port.latest().scope().id());
        assertEquals(Basis.OBSERVED_PORT_DOWN, port.latest().basis());
        assertTrue(port.latest().evidenceIds().contains(down.eventId()));
        assertEquals(8, port.latest().affected().size());
        assertEquals(16, port.latest().eligible().size());
        assertEquals(PortState.DOWN, engine.snapshot().portState());
        assertEquals(OnuState.ONLINE, engine.snapshot().onus().get(onu(9)).state());
        online(engine, 16, 100);
        engine.advanceTo(START.plusSeconds(150));
        assertEquals(Status.OPEN, only(active(engine)).latest().status());
        engine.accept(alarm(PON_PORT_UP, null, 151));
        engine.advanceTo(START.plusSeconds(181));
        assertEquals(Status.RESOLVED, only(engine.snapshot().incidents()).latest().status());
    }

    @Test
    void portDownArrivingBeforeTheClusterSuppressesChildIncidents() {
        var engine = engine();
        online(engine, 16, 0);
        engine.accept(alarm(PON_PORT_DOWN, null, 20));
        for (int i = 1; i <= 16; i++) report(engine, ONU_LOS, i, 30 + i);
        var change = only(engine.snapshot().incidents()).latest();
        assertEquals(Basis.OBSERVED_PORT_DOWN, change.basis());
        assertEquals(16, change.affected().size());
    }

    @Test
    void visibilityLossAndStalenessProduceUnknownReadings() {
        var engine = engine();
        online(engine, 16, 0);
        engine.accept(alarm(OLT_UNREACHABLE, null, 20));
        assertEquals(PortState.UNKNOWN, engine.snapshot().portState());
        assertTrue(engine.snapshot().onus().values().stream().allMatch(o -> o.state() == OnuState.UNKNOWN));
        assertEquals(ONU_ONLINE, engine.snapshot().onus().get(onu(1)).lastObservation().type());
        for (int i = 1; i <= 8; i++) report(engine, ONU_LOS, i, 21);
        engine.accept(alarm(OLT_REACHABLE, null, 25));
        assertEquals(PortState.REACHABLE, engine.snapshot().portState());
        assertEquals(OnuState.UNKNOWN, engine.snapshot().onus().get(onu(1)).state());
        report(engine, ONU_ONLINE, 1, 26);
        assertEquals(OnuState.ONLINE, engine.snapshot().onus().get(onu(1)).state());
        engine.advanceTo(START.plusSeconds(327));
        assertEquals(OnuState.UNKNOWN, engine.snapshot().onus().get(onu(1)).state());
        assertEquals(PortState.UNKNOWN, engine.snapshot().portState());
        assertTrue(engine.snapshot().incidents().isEmpty());
    }

    @Test
    void cohortUsesFreshOnlineServiceStateBeforeTheFirstLoss() {
        var topology = tree(Map.of(6, Service.MAINTENANCE, 7, Service.DECOMMISSIONED), 8, 8);
        var policy = new CorrelationPolicy(Duration.ofSeconds(90), 3, 75, Duration.ofSeconds(30),
                Duration.ofSeconds(10), Duration.ofSeconds(90));
        var engine = new CorrelationEngine(topology, policy);
        online(engine, 16, 0);
        online(engine, 4, 40);
        report(engine, ONU_ONLINE, 6, 60);
        report(engine, ONU_ONLINE, 7, 60);
        report(engine, ONU_OFFLINE_UNKNOWN, 8, 60);
        report(engine, ONU_LOS, 6, 61);
        report(engine, ONU_LOS, 7, 61);
        report(engine, ONU_LOS, 1, 65);
        report(engine, ONU_ONLINE, 5, 66); // Online after the onset. Its original snapshot had expired.
        report(engine, ONU_LOS, 5, 67);
        report(engine, ONU_LOS, 2, 70);
        assertTrue(engine.snapshot().incidents().isEmpty());
        report(engine, ONU_LOS, 3, 71);
        var change = only(active(engine)).latest();
        assertEquals("S2", change.scope().id());
        assertEquals(Set.of(onu(1), onu(2), onu(3), onu(4)), change.eligible());
        assertEquals(Set.of(onu(1), onu(2), onu(3)), change.affected());
    }

    @Test
    void noBaselineOrOnlyTwoEligibleOnusIsInsufficient() {
        var engine = engine();
        for (int i = 1; i <= 16; i++) report(engine, ONU_LOS, i, 10);
        assertTrue(engine.snapshot().incidents().isEmpty());
        online(engine, 2, 20);
        report(engine, ONU_LOS, 1, 21);
        report(engine, ONU_LOS, 2, 22);
        assertTrue(engine.snapshot().incidents().isEmpty());
    }

    @Test
    void aSmallerEarlierPonDenominatorCannotTurnSingleBranchEvidenceIntoAPonOutage() {
        var engine = engine();
        online(engine, 3, 0);
        report(engine, ONU_ONLINE, 9, 0);
        report(engine, ONU_LOS, 9, 5); // Four fresh ONUs in the PON cohort.
        for (int i = 4; i <= 8; i++) report(engine, ONU_ONLINE, i, 6);
        report(engine, ONU_LOS, 1, 10); // All eight are online at the start of S2's outage.
        report(engine, ONU_ONLINE, 9, 11);
        report(engine, ONU_LOS, 2, 12);
        report(engine, ONU_LOS, 3, 13);
        assertTrue(engine.snapshot().incidents().isEmpty());
        for (int i = 4; i <= 6; i++) report(engine, ONU_LOS, i, 14 + i);
        assertEquals("S2", only(active(engine)).latest().scope().id());
    }

    @Test
    void configurableCountFractionAndInclusiveRollingWindowControlOpening() {
        var policy = new CorrelationPolicy(Duration.ofSeconds(10), 4, 50, Duration.ofMinutes(5),
                Duration.ofSeconds(30), Duration.ofSeconds(90));
        var engine = new CorrelationEngine(tree(8, 8), policy);
        online(engine, 16, 0);
        report(engine, ONU_LOS, 1, 10);
        report(engine, ONU_LOS, 2, 15);
        report(engine, ONU_LOS, 3, 19);
        report(engine, ONU_LOS, 4, 21); // The first loss is outside the window.
        assertTrue(engine.snapshot().incidents().isEmpty());
        report(engine, ONU_LOS, 5, 25); // The onset at 15 is still on the boundary.
        var opened = only(active(engine)).latest();
        assertEquals(START.plusSeconds(25), opened.at());
        assertEquals(Set.of(onu(2), onu(3), onu(4), onu(5)), opened.affected());
        assertEquals(8, opened.eligible().size());
    }

    @Test
    void duplicateAndLateDeliveryConvergeOnOneEventTimeHistory() {
        var alarms = new ArrayList<>(OltSimulator.branchLos().stream().map(ReplayFixture::normalize).toList());
        var late = alarms.get(16);
        alarms.set(16, new Alarm(late.eventId(), PON, late.onuId(), late.type(), late.eventTimestamp(), START.plusSeconds(120)));
        var expected = engine();
        alarms.forEach(expected::accept);
        var reordered = engine();
        Collections.reverse(alarms);
        for (Alarm alarm : alarms) {
            reordered.accept(alarm);
            reordered.accept(alarm);
        }
        reordered.accept(new Alarm(late.eventId(), PON, late.onuId(), late.type(), late.eventTimestamp(), START.plusSeconds(140)));
        expected.advanceTo(START.plusSeconds(157));
        reordered.advanceTo(START.plusSeconds(157));
        assertEquals(expected.snapshot(), reordered.snapshot());
        assertEquals(32, reordered.snapshot().observations().size());
        assertEquals(Status.RESOLVED, only(reordered.snapshot().incidents()).latest().status());
    }

    @Test
    void aHealthyThirdBranchKeepsIndependentlySupportedOutagesDistinct() {
        var engine = new CorrelationEngine(tree(8, 8, 4), CorrelationPolicy.defaults());
        online(engine, 20, 0);
        for (int i = 1; i <= 16; i++) report(engine, ONU_LOS, i, 10 + i);
        var incidents = engine.snapshot().incidents();
        assertEquals(2, incidents.size());
        assertEquals(Set.of("S2", "S3"), incidents.stream().map(i -> i.latest().scope().id()).collect(Collectors.toSet()));
        assertTrue(incidents.stream().allMatch(i -> i.active() && i.latest().affected().size() == 8));
        assertEquals(OnuState.ONLINE, engine.snapshot().onus().get(onu(20)).state());
    }

    @Test
    void anEarlierBranchOutageDeliveredLateKeepsTheOtherBranchIdentity() {
        var engine = new CorrelationEngine(tree(8, 8, 4), CorrelationPolicy.defaults());
        online(engine, 20, 0);
        for (int i = 9; i <= 16; i++) report(engine, ONU_LOS, i, 40 + i);
        String established = only(active(engine)).id();
        for (int i = 1; i <= 8; i++) {
            var delayed = alarm(ONU_LOS, onu(i), 10 + i);
            engine.accept(new Alarm(delayed.eventId(), PON, onu(i), ONU_LOS, delayed.eventTimestamp(), START.plusSeconds(60)));
        }
        assertEquals(2, active(engine).size());
        assertTrue(active(engine).stream().anyMatch(i -> i.id().equals(established) && i.latest().scope().id().equals("S3")));
    }

    @Test
    void lateRecoveredOutageOnTheSameSplitterPreservesPublishedIdsAndBothHistories() {
        var engine = engine();
        online(engine, 16, 0);
        var deliveries = new ArrayList<Alarm>();
        for (int i = 1; i <= 8; i++) deliveries.add(alarm(ONU_LOS, onu(i), 60 + i));
        deliveries.forEach(engine::accept);
        Incident published = only(active(engine));
        assertEquals(START.plusSeconds(66), published.timeline().getFirst().at());

        // Six earlier LOS at 6..11s, followed by recovery at 16..21s. The whole backfill arrives at 75s.
        for (var type : List.of(ONU_LOS, ONU_ONLINE)) {
            int firstOffset = type == ONU_LOS ? 5 : 15;
            for (int i = 1; i <= 6; i++) {
                Alarm late = receivedAt(alarm(type, onu(i), firstOffset + i), 75);
                deliveries.add(late);
                engine.accept(late);
                assertEquals(published.id(), only(active(engine)).id());
            }
        }
        List<Incident> histories = engine.snapshot().incidents();
        assertEquals(2, histories.size());
        Incident earlier = histories.getFirst();
        assertEquals(START.plusSeconds(11), earlier.timeline().getFirst().at());
        assertEquals(Status.RESOLVED, earlier.latest().status());
        assertEquals(START.plusSeconds(51), earlier.latest().at());
        assertNotEquals(published.id(), earlier.id());
        assertEquals(published, histories.getLast());

        for (Alarm delivered : deliveries.reversed()) engine.accept(receivedAt(delivered, 85));
        assertEquals(histories, engine.snapshot().incidents());
        assertEquals(36, engine.snapshot().observations().size());
    }

    @Test
    void lateOnsetsCanCorrectTheOpeningTimeOfAnAlreadyPublishedIncident() {
        var engine = engine();
        online(engine, 16, 0);
        for (int i = 1; i <= 6; i++) report(engine, ONU_LOS, i, 40 + i);
        Incident published = only(active(engine));
        assertEquals(START.plusSeconds(46), published.timeline().getFirst().at());
        for (int i = 1; i <= 6; i++) engine.accept(receivedAt(alarm(ONU_LOS, onu(i), 10 + i), 60));
        Incident corrected = only(engine.snapshot().incidents());
        assertEquals(published.id(), corrected.id());
        assertEquals(START.plusSeconds(16), corrected.timeline().getFirst().at());
    }

    private static Alarm receivedAt(Alarm alarm, long second) {
        return new Alarm(alarm.eventId(), alarm.ponPort(), alarm.onuId(), alarm.type(),
                alarm.eventTimestamp(), START.plusSeconds(second));
    }

    @Test
    void separateTimeWindowsOnTheSamePonKeepSeparateIncidents() {
        var engine = engine();
        online(engine, 16, 0);
        for (int i = 1; i <= 8; i++) report(engine, ONU_LOS, i, 10 + i);
        for (int i = 9; i <= 16; i++) report(engine, ONU_LOS, i, 150 + i);
        assertEquals(2, active(engine).size());
        assertTrue(active(engine).stream().allMatch(i -> i.latest().scope().kind() == Kind.SPLITTER));
    }

    @Test
    void aNewWaveAcrossTwoBranchesCannotAbsorbAnOlderBranchOutage() {
        var engine = new CorrelationEngine(tree(4, 4, 4), CorrelationPolicy.defaults());
        online(engine, 12, 0);
        for (int i = 1; i <= 4; i++) report(engine, ONU_LOS, i, 10 + i);
        for (int i = 5; i <= 12; i++) report(engine, ONU_LOS, i, 150 + i);
        assertEquals(3, active(engine).size());
        assertTrue(active(engine).stream().allMatch(i -> i.latest().scope().kind() == Kind.SPLITTER));
    }

    @Test
    void aFlapRestartsTheHoldWithoutOpeningAnotherIncident() {
        var engine = engine();
        OltSimulator.branchLos().stream().map(ReplayFixture::normalize).forEach(engine::accept);
        report(engine, ONU_LOS, 1, 140);
        engine.advanceTo(START.plusSeconds(157));
        assertEquals(Status.OPEN, only(active(engine)).latest().status());
        report(engine, ONU_ONLINE, 1, 160);
        engine.advanceTo(START.plusSeconds(190));
        assertEquals(START.plusSeconds(190), only(engine.snapshot().incidents()).latest().at());
        assertEquals(Status.RESOLVED, only(engine.snapshot().incidents()).latest().status());
    }

    @Test
    void staleEvidenceDuringTheRecoveryHoldCannotResolveAnIncident() {
        var policy = new CorrelationPolicy(Duration.ofSeconds(90), 3, 75, Duration.ofSeconds(10),
                Duration.ofSeconds(30), Duration.ofSeconds(90));
        var engine = new CorrelationEngine(tree(4, 4), policy);
        online(engine, 8, 0);
        for (int i = 1; i <= 3; i++) report(engine, ONU_LOS, i, i);
        online(engine, 8, 5);
        assertEquals(Status.RECOVERING, only(active(engine)).latest().status());
        engine.advanceTo(START.plusSeconds(36));
        assertEquals(PortState.UNKNOWN, engine.snapshot().portState());
        assertEquals(Status.OPEN, only(active(engine)).latest().status());
    }

    @Test
    void aLosStampedAtTheRecoveryDeadlineTakesPrecedenceOverTheTimer() {
        var engine = engine();
        OltSimulator.branchLos().stream().map(ReplayFixture::normalize).forEach(engine::accept);
        engine.advanceTo(START.plusSeconds(157));
        Incident beforeDelivery = only(engine.snapshot().incidents());
        assertEquals(Status.RESOLVED, beforeDelivery.latest().status());
        report(engine, ONU_LOS, 1, 157);
        Incident corrected = only(engine.snapshot().incidents());
        assertEquals(beforeDelivery.id(), corrected.id());
        assertEquals(Status.OPEN, corrected.latest().status());
        assertEquals(Set.of(onu(1)), corrected.latest().affected());
    }

    @Test
    void losingOltVisibilityDuringRecoveryRequiresFreshOnlineEvidenceAfterReturn() {
        var engine = engine();
        OltSimulator.branchLos().stream().map(ReplayFixture::normalize).forEach(engine::accept);
        engine.accept(alarm(OLT_UNREACHABLE, null, 140));
        assertEquals(PortState.UNKNOWN, engine.snapshot().portState());
        assertEquals(OnuState.UNKNOWN, engine.snapshot().onus().get(onu(1)).state());
        engine.accept(alarm(OLT_REACHABLE, null, 150));
        online(engine, 7, 151);
        engine.advanceTo(START.plusSeconds(190));
        assertEquals(Status.OPEN, only(active(engine)).latest().status());
        report(engine, ONU_ONLINE, 8, 191);
        engine.advanceTo(START.plusSeconds(221));
        assertEquals(Status.RESOLVED, only(engine.snapshot().incidents()).latest().status());
    }

    @Test
    void aLateOrFutureDatedAlarmIsPreservedWithoutChangingTheProjection() {
        var engine = engine();
        online(engine, 16, 0);
        engine.accept(new Alarm("late", PON, onu(1), ONU_LOS, START.plusSeconds(1), START.plusSeconds(100)));
        engine.accept(new Alarm("future", PON, onu(2), ONU_LOS, START.plusSeconds(200), START.plusSeconds(101)));
        assertEquals(18, engine.snapshot().observations().size());
        assertEquals(OnuState.ONLINE, engine.snapshot().onus().get(onu(1)).state());
        assertEquals(OnuState.ONLINE, engine.snapshot().onus().get(onu(2)).state());
        assertTrue(engine.snapshot().incidents().isEmpty());
    }
}
