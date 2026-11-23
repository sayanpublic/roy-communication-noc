package in.roycommunication.noc.adapter.syrotech;

import in.roycommunication.noc.correlation.Alarm;
import in.roycommunication.noc.correlation.CorrelationEngine;
import in.roycommunication.noc.correlation.CorrelationPolicy;
import in.roycommunication.noc.correlation.FiberTopology;
import in.roycommunication.noc.correlation.Incident;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static in.roycommunication.noc.adapter.syrotech.OnuStatusSnapshot.*;
import static in.roycommunication.noc.adapter.syrotech.OnuStatusSnapshot.Status.*;
import static in.roycommunication.noc.correlation.Alarm.Type.*;
import static in.roycommunication.noc.correlation.CorrelationEngine.OnuState;
import static org.junit.jupiter.api.Assertions.*;

class SyrotechEponAdapterTest {
    private static final Instant START = Instant.parse("2026-01-01T00:00:00Z");
    private static final String PON = "PON-03";
    private static final String ONU = "ONU-01";
    private static final List<String> BRANCH_MEMBERS = List.of(ONU, "ONU-02", "ONU-03");
    private final SyrotechEponAdapter adapter = new SyrotechEponAdapter(Duration.ofSeconds(30));

    @ParameterizedTest
    @CsvSource({"LOS, ONU_LOS", "Dying Gasp, ONU_DYING_GASP"})
    void aRetainedReasonCannotClassifyANewOutageEvenWhenItIsOnlySecondsOld(String text, Alarm.Type cause) {
        var firstDown = reason(text, 4);
        var secondDown = row(OFFLINE, 10, firstDown);
        var replay = List.of(row(ONLINE, 0, null), row(OFFLINE, 5, firstDown), row(ONLINE, 7, firstDown),
                secondDown, row(ONLINE, 12, firstDown), row(OFFLINE, 15, reason(text, 14)));

        var results = adapter.normalize(replay);
        assertEquals(List.of(ONU_ONLINE, cause, ONU_ONLINE, ONU_OFFLINE_UNKNOWN, ONU_ONLINE, cause),
                results.stream().map(result -> result.alarm().type()).toList());
        assertEquals(secondDown, results.get(3).source());
        assertEquals(new Alarm(secondDown.eventId(), PON, ONU, ONU_OFFLINE_UNKNOWN,
                START.plusSeconds(10), START.plusSeconds(10).plusMillis(250)), results.get(3).alarm());
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"offline", "17", "LOS Recovery", "power off", "ONU Link LOST", "PON LOS", "LOS / Dying Gasp"})
    void unsupportedOrMissingTextStaysUnknownEvenWithAFreshTimestamp(String text) {
        var rows = List.of(row(ONLINE, 0, null), row(OFFLINE, 10, reason(text, 9)));
        assertEquals(ONU_OFFLINE_UNKNOWN, adapter.normalize(rows).getLast().alarm().type());
    }

    @Test
    void onlyEvidenceForTheObservedTransitionCanClassifyLaterOfflineReadings() {
        var explicit = reason("  Loss of Signal  ", 8);
        var rows = List.of(row(ONLINE, 0, null), row(OFFLINE, 10, null),
                row(OFFLINE, 11, new LastDownReason("LOS", null)), row(OFFLINE, 12, explicit),
                row(OFFLINE, 13, reason("Dying Gasp", 12)), row(OFFLINE, 38, explicit), row(OFFLINE, 39, explicit));

        var results = adapter.normalize(rows);
        assertEquals(List.of(ONU_ONLINE, ONU_OFFLINE_UNKNOWN, ONU_OFFLINE_UNKNOWN, ONU_LOS,
                        ONU_OFFLINE_UNKNOWN, ONU_LOS, ONU_OFFLINE_UNKNOWN),
                results.stream().map(result -> result.alarm().type()).toList());
        assertEquals(explicit, results.get(3).source().lastDownReason());
        assertEquals(START.plusSeconds(12), results.get(3).alarm().eventTimestamp());
    }

    @Test
    void conflictingFreshReasonsLeaveTheOutageUnknownAndRecoveryStartsANewHistory() {
        var rows = List.of(row(ONLINE, 0, null), row(OFFLINE, 5, reason("LOS", 4)),
                row(OFFLINE, 6, reason("Dying Gasp", 4)), row(OFFLINE, 7, reason("LOS", 4)),
                row(ONLINE, 9, null), row(OFFLINE, 12, reason("Dying Gasp", 11)));
        var results = adapter.normalize(rows.reversed());
        assertEquals(List.of(ONU_ONLINE, ONU_OFFLINE_UNKNOWN, ONU_OFFLINE_UNKNOWN, ONU_OFFLINE_UNKNOWN,
                        ONU_ONLINE, ONU_DYING_GASP),
                results.stream().map(result -> result.alarm().type()).toList());
        assertEquals(rows, results.stream().map(SyrotechEponAdapter.Observation::source).toList());
    }

    @ParameterizedTest
    @CsvSource({"9, ONU_OFFLINE_UNKNOWN", "10, ONU_DYING_GASP", "11, ONU_OFFLINE_UNKNOWN"})
    void initiallyOfflineRequiresACoincidentDisconnectTime(long reasonSecond, Alarm.Type expected) {
        var initial = row(OFFLINE, 10, reason("Dying Gasp", reasonSecond));
        assertEquals(expected, adapter.normalize(List.of(initial)).getFirst().alarm().type());
    }

    @ParameterizedTest
    @EnumSource(Status.class)
    void aReasonSeenBeforeItsClaimedTimeStaysInvalidAcrossPolls(Status initialStatus) {
        var invalid = reason("LOS", 4);
        var rows = List.of(row(initialStatus, 2, invalid), row(ONLINE, 3, null), row(OFFLINE, 5, invalid));
        assertEquals(ONU_OFFLINE_UNKNOWN, adapter.normalize(rows).getLast().alarm().type());
    }

    @Test
    void sampleOrderAndHistoryAreIndependentForEachPonAndOnu() {
        var down = reason("LOS", 5);
        var original = row(OFFLINE, 10, down);
        var delayed = new OnuStatusSnapshot(original.eventId(), PON, ONU, OFFLINE, original.observedAt(),
                START.plusSeconds(25), down);
        var mixed = List.of(row(ONLINE, 0, null), row("PON-04", ONU, ONLINE, 8, null),
                row(PON, "ONU-02", ONLINE, 8, null), delayed, row("PON-04", ONU, OFFLINE, 12, down),
                row(PON, "ONU-02", OFFLINE, 12, down));

        var results = adapter.normalize(mixed.reversed());
        assertEquals(adapter.normalize(mixed), results);
        assertEquals(List.of(ONU_ONLINE, ONU_ONLINE, ONU_ONLINE, ONU_LOS, ONU_OFFLINE_UNKNOWN, ONU_OFFLINE_UNKNOWN),
                results.stream().map(result -> result.alarm().type()).toList());
        assertEquals(delayed.receivedAt(), results.get(3).alarm().receiveTimestamp());
    }

    @Test
    void correlationCountsOnlyLosSupportedByTheCurrentOutage() {
        var rows = new ArrayList<OnuStatusSnapshot>();
        var retained = reason("LOS", -1);
        for (String member : BRANCH_MEMBERS) {
            rows.add(row(PON, member, ONLINE, 0, retained));
            rows.add(row(PON, member, OFFLINE, 3, null));
            rows.add(row(PON, member, OFFLINE, 4, retained));
            rows.add(row(PON, member, ONLINE, 7, retained));
            rows.add(row(PON, member, OFFLINE, 10, reason("LOS", 9)));
        }
        rows.add(row(PON, "ONU-09", ONLINE, 0, null));
        var engine = branchEngine();
        var alarms = adapter.normalize(rows).stream().map(SyrotechEponAdapter.Observation::alarm).toList();

        Instant firstOutage = START.plusSeconds(4);
        alarms.stream().filter(alarm -> !alarm.eventTimestamp().isAfter(firstOutage)).forEach(engine::accept);
        var first = engine.snapshot();
        assertTrue(first.incidents().isEmpty());
        for (String member : BRANCH_MEMBERS) assertEquals(OnuState.OFFLINE_UNKNOWN, first.onus().get(member).state());

        alarms.forEach(engine::accept); // Includes redelivery of the first readings with their original IDs.
        var second = engine.snapshot();
        assertEquals(rows.size(), second.observations().size());
        assertEquals(1, second.incidents().size());
        var change = second.incidents().getFirst().latest();
        assertEquals("S2", change.scope().id());
        assertEquals(3, change.affected().size());
        assertEquals(OnuState.ONLINE, second.onus().get("ONU-09").state());
    }

    @Test
    void aLaterPollCanConfirmThreeEarlierUnknownOutagesOnOneSplitter() {
        var rows = new ArrayList<OnuStatusSnapshot>();
        var confirmations = new ArrayList<String>();
        for (int i = 0; i < BRANCH_MEMBERS.size(); i++) {
            String member = BRANCH_MEMBERS.get(i);
            var cause = reason("LOS", 10 + i);
            rows.add(row(PON, member, ONLINE, 0, null));
            rows.add(row(PON, member, OFFLINE, 10 + i, null));
            rows.add(row(PON, member, OFFLINE, 15, null));
            var confirmation = row(PON, member, OFFLINE, 20 + 2 * i, cause);
            rows.add(confirmation);
            confirmations.add(confirmation.eventId());
            rows.add(row(PON, member, OFFLINE, 25, cause));
        }
        rows.add(row(PON, ONU, OFFLINE, 21, reason("LOS", 10)));
        rows.add(row(PON, ONU, OFFLINE, 23, reason("LOS", 10)));
        rows.add(row(PON, "ONU-04", ONLINE, 0, null));
        rows.add(row(PON, "ONU-09", ONLINE, 0, null));
        var engine = branchEngine();
        var alarms = adapter.normalize(rows).stream().map(SyrotechEponAdapter.Observation::alarm).toList();
        Instant thirdConfirmation = START.plusSeconds(24);
        for (Alarm alarm : alarms) {
            if (alarm.eventTimestamp().isAfter(thirdConfirmation)) break;
            engine.accept(alarm);
            if (alarm.type() == ONU_OFFLINE_UNKNOWN) {
                assertEquals(OnuState.OFFLINE_UNKNOWN, engine.snapshot().onus().get(alarm.onuId()).state());
            }
            if (alarm.eventTimestamp().isBefore(thirdConfirmation)) {
                assertTrue(engine.snapshot().incidents().isEmpty());
            }
        }

        var snapshot = engine.snapshot();
        assertEquals(1, snapshot.incidents().size());
        var incident = snapshot.incidents().getFirst();
        assertEquals(1, incident.timeline().size());
        var opening = incident.latest();
        assertEquals(Incident.Basis.INFERRED_SHARED_LOS, opening.basis());
        assertEquals("S2", opening.scope().id());
        assertEquals(START.plusSeconds(12), opening.at());
        assertEquals(Set.copyOf(BRANCH_MEMBERS), opening.affected());
        assertEquals(Set.of(ONU, "ONU-02", "ONU-03", "ONU-04"), opening.eligible());
        assertEquals(confirmations, opening.evidenceIds());
        assertEquals(6L, snapshot.observations().stream().filter(alarm -> alarm.type() == ONU_OFFLINE_UNKNOWN).count());
        assertEquals(OnuState.ONLINE, snapshot.onus().get("ONU-09").state());
        alarms.reversed().forEach(engine::accept);
        assertEquals(snapshot.incidents(), engine.snapshot().incidents());
        assertEquals(rows.size(), engine.snapshot().observations().size());
    }

    @ParameterizedTest
    @CsvSource({"100, 12, 1", "101, 30, 0"})
    void correlationUsesTheSpreadOfFirstOfflineReadingsEvenWhenLosPollsHaveADifferentSpread(
            int lastOnset, int firstConfirmation, int expectedIncidents) {
        var rows = new ArrayList<OnuStatusSnapshot>();
        int[] onsets = {10, 95, lastOnset};
        int[] confirmations = {firstConfirmation, 106, 115};
        for (int i = 0; i < BRANCH_MEMBERS.size(); i++) {
            String member = BRANCH_MEMBERS.get(i);
            rows.add(row(PON, member, ONLINE, 0, null));
            rows.add(row(PON, member, OFFLINE, onsets[i], null));
            rows.add(row(PON, member, OFFLINE, confirmations[i], reason("LOS", onsets[i])));
        }
        rows.add(row(PON, "ONU-09", ONLINE, 0, null));
        var engine = branchEngine();
        adapter.normalize(rows).forEach(result -> engine.accept(result.alarm()));
        assertEquals(expectedIncidents, engine.snapshot().incidents().size());
        for (String member : BRANCH_MEMBERS) assertEquals(OnuState.LOS, engine.snapshot().onus().get(member).state());
    }

    private static CorrelationEngine branchEngine() {
        var inventory = new ArrayList<FiberTopology.Onu>();
        for (String member : List.of(ONU, "ONU-02", "ONU-03", "ONU-04")) {
            inventory.add(new FiberTopology.Onu(member, "S2", FiberTopology.Service.ACTIVE));
        }
        inventory.add(new FiberTopology.Onu("ONU-09", "S3", FiberTopology.Service.ACTIVE));
        var topology = new FiberTopology(PON, List.of(new FiberTopology.Splitter("S2", PON, "F2"),
                new FiberTopology.Splitter("S3", PON, "F3")), inventory);
        return new CorrelationEngine(topology, CorrelationPolicy.defaults());
    }

    private static LastDownReason reason(String text, long second) {
        return new LastDownReason(text, START.plusSeconds(second));
    }

    private static OnuStatusSnapshot row(Status status, long second, LastDownReason reason) {
        return row(PON, ONU, status, second, reason);
    }

    private static OnuStatusSnapshot row(String pon, String onu, Status status, long second, LastDownReason reason) {
        Instant at = START.plusSeconds(second);
        return new OnuStatusSnapshot(pon + "/" + onu + "/" + second, pon, onu, status, at, at.plusMillis(250), reason);
    }
}
