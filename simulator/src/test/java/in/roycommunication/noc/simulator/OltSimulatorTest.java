package in.roycommunication.noc.simulator;

import in.roycommunication.noc.simulator.OltSimulator.Event;
import in.roycommunication.noc.simulator.OltSimulator.EventType;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OltSimulatorTest {
    private static final List<String> ALL_ONUS = List.of(
            "ONU-01", "ONU-02", "ONU-03", "ONU-04", "ONU-05", "ONU-06", "ONU-07", "ONU-08",
            "ONU-09", "ONU-10", "ONU-11", "ONU-12", "ONU-13", "ONU-14", "ONU-15", "ONU-16");

    @Test
    void onlyTheEightOnusOnSplitterS2ReportLosAndThenRecover() {
        List<Event> events = OltSimulator.branchLos();
        List<String> s2 = ALL_ONUS.subList(0, 8);

        assertEquals(32, events.size());
        assertPhase(events.subList(0, 16), EventType.ONU_ONLINE, ALL_ONUS);
        List<Event> losses = events.subList(16, 24);
        assertPhase(losses, EventType.ONU_LOS, s2);
        assertEquals(Duration.ofSeconds(35),
                Duration.between(losses.getFirst().eventTimestamp(), losses.getLast().eventTimestamp()));
        assertPhase(events.subList(24, 32), EventType.ONU_ONLINE, s2);
    }

    @Test
    void thePowerScenarioStartsOnlineAndEndsWithOneDyingGasp() {
        List<Event> events = OltSimulator.dyingGasp();

        assertEquals(17, events.size());
        assertPhase(events.subList(0, 16), EventType.ONU_ONLINE, ALL_ONUS);
        assertPhase(events.subList(16, 17), EventType.ONU_DYING_GASP, List.of("ONU-09"));
    }

    private static void assertPhase(List<Event> events, EventType type, List<String> expectedOnus) {
        assertEquals(expectedOnus, events.stream().map(Event::onuId).toList());
        for (Event event : events) {
            assertEquals(type, event.eventType(), event.eventId());
        }
    }

    @Test
    void eventsHaveUniqueIdsOnePonAndOrderedTimestampsWithFixedDeliveryDelay() {
        var seenIds = new HashSet<String>();
        Instant previousTime = Instant.MIN;
        for (List<Event> scenario : List.of(OltSimulator.branchLos(), OltSimulator.dyingGasp())) {
            for (Event event : scenario) {
                assertFalse(event.eventId().isBlank());
                assertTrue(seenIds.add(event.eventId()), event.eventId());
                assertEquals("PON-03", event.ponPort());
                assertFalse(event.eventTimestamp().isBefore(previousTime), event.eventId());
                assertEquals(event.eventTimestamp().plusMillis(250), event.receiveTimestamp());
                previousTime = event.eventTimestamp();
            }
        }
    }

    @Test
    void replayPrintsBothScenariosAsRepeatableSixColumnCsv() {
        String output = renderReplay();
        assertEquals(output, renderReplay());

        List<String> lines = output.lines().toList();
        assertEquals(50, lines.size());
        assertEquals("eventId,ponPort,onuId,eventType,eventTimestamp,receiveTimestamp", lines.getFirst());
        assertEquals("branch-los-17,PON-03,ONU-01,ONU_LOS,2026-01-01T00:00:30Z,2026-01-01T00:00:30.250Z",
                lines.get(17));
        assertEquals("branch-los-25,PON-03,ONU-01,ONU_ONLINE,2026-01-01T00:02:00Z,2026-01-01T00:02:00.250Z",
                lines.get(25));
        assertEquals("dying-gasp-17,PON-03,ONU-09,ONU_DYING_GASP,2026-01-01T01:00:30Z,2026-01-01T01:00:30.250Z",
                lines.getLast());
    }

    private static String renderReplay() {
        var bytes = new ByteArrayOutputStream();
        try (var output = new PrintStream(bytes, false, StandardCharsets.UTF_8)) {
            OltSimulator.printReplay(output);
        }
        return bytes.toString(StandardCharsets.UTF_8);
    }
}
