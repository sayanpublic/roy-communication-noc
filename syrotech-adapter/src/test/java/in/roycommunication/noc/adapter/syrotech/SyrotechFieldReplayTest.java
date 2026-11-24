package in.roycommunication.noc.adapter.syrotech;

import in.roycommunication.noc.correlation.Alarm;
import in.roycommunication.noc.correlation.CorrelationEngine;
import in.roycommunication.noc.correlation.CorrelationPolicy;
import in.roycommunication.noc.correlation.FiberTopology;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static in.roycommunication.noc.adapter.syrotech.SyrotechFieldReplay.*;
import static in.roycommunication.noc.correlation.Alarm.Type.*;
import static in.roycommunication.noc.correlation.CorrelationEngine.OnuState;
import static org.junit.jupiter.api.Assertions.*;

class SyrotechFieldReplayTest {
    private static final String PON = "PON-A";
    private static final Duration TWO_SECONDS = Duration.ofSeconds(2);
    private static Result capture;

    @BeforeAll
    static void loadCapture() throws IOException {
        capture = RedactedPortalSample.replay();
    }

    @Test
    void theTwoGaspTimeoutPairsEachProduceOnePowerLossEpisode() {
        assertEquals(List.of("ONU-A", "ONU-C"), capture.powerEpisodes().stream()
                .map(episode -> episode.dyingGasp().onu().id()).toList());
        assertEquals(List.of(LocalDateTime.parse("2026-11-23T22:41:00"), LocalDateTime.parse("2026-11-23T23:56:55")),
                capture.powerEpisodes().stream().map(episode -> episode.dyingGasp().oltLocalTime()).toList());
        for (PowerEpisode episode : capture.powerEpisodes()) {
            var gasp = episode.dyingGasp();
            assertEquals("ONU Dying Gasp", gasp.event());
            assertEquals(1, episode.followUps().size());
            var timeout = episode.followUps().getFirst();
            assertEquals(gasp.onu(), timeout.onu());
            assertEquals(gasp.oltLocalTime().plusSeconds(2), timeout.oltLocalTime());
            assertEquals("ONU Deregister", timeout.event());
            assertEquals("MPCP TIMEOUT.", timeout.diagnostic());
            assertTrue(capture.journal().stream().anyMatch(entry -> entry.source().equals(timeout) && entry.transition().isEmpty()));
            var status = statusOf(gasp.onu().id());
            assertEquals("Offline", status.source().status());
            assertEquals("Power Off", status.source().lastDeregisterReason());
            assertEquals(gasp.oltLocalTime(), status.source().lastDeregister());
            assertEquals(OnuState.DYING_GASP, status.state());
        }
        assertEquals(2L, transitions(capture).stream().filter(a -> a.type() == ONU_DYING_GASP).count());
    }

    @Test
    void wireDownAndOnuInitiatedDeregistrationKeepTheirNativeOrderThroughRecovery() {
        var rows = capture.journal().stream().filter(e -> e.source().onu().id().equals("ONU-B")).toList();
        assertEquals(List.of(16, 15, 14, 13, 9, 8, 7, 6), rows.stream().map(e -> e.source().tableRow()).toList());
        assertEquals(rows.get(4).source().oltLocalTime(), rows.get(5).source().oltLocalTime());
        for (var start : List.of(0, 4)) {
            var cycle = rows.subList(start, start + 4);
            assertEquals(List.of("ONU Deregister", "ONU Register", "ONU AUTH Success", "ONU Finish"),
                    cycle.stream().map(e -> e.source().event()).toList());
            assertEquals("MPCP ONU initiates DEREG.", cycle.getFirst().source().diagnostic());
            assertEquals(List.of(ONU_OFFLINE_UNKNOWN, ONU_ONLINE), cycle.stream()
                    .flatMap(e -> e.transition().stream()).map(Alarm::type).toList());
            assertEquals(ONU_ONLINE, cycle.getLast().transition().orElseThrow().type());
        }
        var current = statusOf("ONU-B");
        assertEquals("Wire Down", current.source().lastDeregisterReason());
        assertEquals(rows.get(4).source().oltLocalTime(), current.source().lastDeregister());
        assertEquals(OnuState.ONLINE, current.state());
    }

    @Test
    void currentStatusWinsOverHistoricalPowerOffAndAnAbsentReasonRemainsUnknown() {
        var recovered = statusOf("ONU-E");
        assertEquals("Online", recovered.source().status());
        assertEquals("Power Off", recovered.source().lastDeregisterReason());
        assertTrue(recovered.source().lastRegister().isAfter(recovered.source().lastDeregister()));
        assertEquals(OnuState.ONLINE, recovered.state());
        var offline = statusOf("ONU-D");
        assertEquals("Offline", offline.source().status());
        assertEquals("N/A", offline.source().lastDeregisterReason());
        assertNull(offline.source().lastRegister());
        assertNull(offline.source().lastDeregister());
        assertEquals(OnuState.OFFLINE_UNKNOWN, offline.state());
    }

    @Test
    void theEngineReplaysTheCapturedLogWithOnlyElapsedTime() {
        var ids = capture.statuses().stream().map(status -> status.source().onu().id()).toList();
        var engine = engine(ids);
        var firstLocal = capture.journal().getFirst().source().oltLocalTime();
        for (LogObservation entry : capture.journal()) {
            entry.transition().ifPresent(alarm -> {
                var elapsed = Duration.between(firstLocal, entry.source().oltLocalTime());
                assertEquals(Instant.EPOCH.plus(elapsed), alarm.eventTimestamp());
                assertEquals(alarm.eventTimestamp(), alarm.receiveTimestamp());
                engine.accept(alarm);
            });
        }
        var snapshot = engine.snapshot();
        assertEquals(7, snapshot.observations().size());
        assertTrue(snapshot.incidents().isEmpty());
        assertEquals(OnuState.DYING_GASP, snapshot.onus().get("ONU-C").state());
    }

    @Test
    void threeEligibleOnusReportingTheWireDownDiagnosticCannotTriggerSharedLos() {
        var ids = List.of("TEST-1", "TEST-2", "TEST-3");
        var engine = engine(ids);
        var source = capture.journal().stream().map(LogObservation::source)
                .filter(row -> row.diagnostic().equals("MPCP ONU initiates DEREG.")).findFirst().orElseThrow();
        var log = new ArrayList<LogRow>();
        for (int i = 0; i < ids.size(); i++) {
            var onu = new Onu(PON, ids.get(i));
            log.add(new LogRow(6 - i, source.oltLocalTime().minusSeconds(1), onu, "ONU Finish", ""));
            log.add(new LogRow(3 - i, source.oltLocalTime(), onu, source.event(), source.diagnostic()));
        }
        transitions(replay(log, List.of(), TWO_SECONDS)).forEach(engine::accept);
        var snapshot = engine.snapshot();
        assertTrue(snapshot.incidents().isEmpty());
        for (String id : ids) assertEquals(OnuState.OFFLINE_UNKNOWN, snapshot.onus().get(id).state());
    }

    @ParameterizedTest
    @CsvSource({"PON-B, ONU-A, 2", "PON-A, ONU-X, 2", "PON-A, ONU-A, 3"})
    void aTimeoutOnAnotherPonOrOnuOrOutsideTheWindowStaysUnexplained(String pon, String id, int delay) {
        var gasp = capture.powerEpisodes().getFirst().dyingGasp();
        var timeout = new LogRow(1, gasp.oltLocalTime().plusSeconds(delay), new Onu(pon, id),
                "ONU Deregister", "MPCP TIMEOUT.");
        var result = replay(List.of(timeout, gasp), List.of(), TWO_SECONDS);
        assertEquals(List.of(ONU_DYING_GASP, ONU_OFFLINE_UNKNOWN), transitions(result).stream().map(Alarm::type).toList());
        assertTrue(result.powerEpisodes().getFirst().followUps().isEmpty());
    }

    @Test
    void registrationSeparatesPowerEpisodesAndPreventsAnOldStatusReasonFromAttaching() {
        var first = capture.powerEpisodes().getFirst().dyingGasp();
        var onu = first.onu();
        var time = first.oltLocalTime();
        var registered = new LogRow(5, time.plusSeconds(1), onu, "ONU Register", "");
        var timeout = new LogRow(4, time.plusSeconds(2), onu, "ONU Deregister", "MPCP TIMEOUT.");
        var second = new LogRow(3, time.plusSeconds(10), onu, "ONU Dying Gasp", "");
        var followUp = new LogRow(2, time.plusSeconds(12), onu, "ONU Deregister", "MPCP TIMEOUT.");
        var stale = new StatusRow(onu, "Offline", null, time, "Power Off");
        var result = replay(List.of(first, registered, timeout, second, followUp), List.of(stale), TWO_SECONDS);
        assertEquals(List.of(ONU_DYING_GASP, ONU_OFFLINE_UNKNOWN, ONU_DYING_GASP),
                transitions(result).stream().map(Alarm::type).toList());
        assertEquals(List.of(new PowerEpisode(first, List.of()), new PowerEpisode(second, List.of(followUp))),
                result.powerEpisodes());
        assertEquals(OnuState.OFFLINE_UNKNOWN, result.statuses().getFirst().state());
    }

    @Test
    void repeatedGaspEvidenceCannotJoinTwoWidelySeparatedPowerReports() {
        var gasp = capture.powerEpisodes().getFirst().dyingGasp();
        var onu = gasp.onu();
        var time = gasp.oltLocalTime();
        var repeat = new LogRow(5, time.plusSeconds(1), onu, "ONU Dying Gasp", "");
        var timeout = new LogRow(4, time.plusSeconds(3), onu, "ONU Deregister", "MPCP TIMEOUT");
        var later = new LogRow(3, time.plusHours(1), onu, "ONU Dying Gasp", "");
        var result = replay(List.of(gasp, repeat, timeout, later), List.of(), TWO_SECONDS);
        assertEquals(List.of(new PowerEpisode(gasp, List.of(repeat, timeout)), new PowerEpisode(later, List.of())),
                result.powerEpisodes());
        assertEquals(List.of(ONU_DYING_GASP, ONU_DYING_GASP), transitions(result).stream().map(Alarm::type).toList());
    }

    @Test
    void repeatingTheCaptureInEitherOrderPreservesEveryDistinctSourceRowOnce() {
        List<LogRow> log = capture.journal().stream().map(LogObservation::source).toList();
        var repeated = new ArrayList<>(log.reversed());
        repeated.addAll(log);
        assertEquals(14, log.size());
        assertEquals(5, capture.statuses().size());
        assertEquals(capture, replay(repeated, capture.statuses().stream().map(StatusObservation::source).toList(),
                TWO_SECONDS));
        var row = log.getFirst();
        repeated.add(new LogRow(row.tableRow(), row.oltLocalTime(), row.onu(), "different row", ""));
        assertThrows(IllegalArgumentException.class, () -> replay(repeated, List.of(), TWO_SECONDS));
    }

    private static List<Alarm> transitions(Result result) {
        return result.journal().stream().flatMap(e -> e.transition().stream()).toList();
    }

    private static StatusObservation statusOf(String id) {
        return capture.statuses().stream().filter(s -> s.source().onu().id().equals(id)).findFirst().orElseThrow();
    }

    private static CorrelationEngine engine(List<String> ids) {
        var inventory = ids.stream().map(id -> new FiberTopology.Onu(id, PON, FiberTopology.Service.ACTIVE)).toList();
        return new CorrelationEngine(new FiberTopology(PON, List.of(), inventory), CorrelationPolicy.defaults());
    }
}
