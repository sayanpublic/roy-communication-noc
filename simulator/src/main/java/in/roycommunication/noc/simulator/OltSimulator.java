package in.roycommunication.noc.simulator;

import java.io.PrintStream;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

public final class OltSimulator {
    public enum EventType { ONU_ONLINE, ONU_LOS, ONU_DYING_GASP }

    public record Event(String eventId, String ponPort, String onuId, EventType eventType,
                        Instant eventTimestamp, Instant receiveTimestamp) {}

    private static final String PON_PORT = "PON-03";
    private static final List<String> SPLITTER_S2 = List.of(
            "ONU-01", "ONU-02", "ONU-03", "ONU-04", "ONU-05", "ONU-06", "ONU-07", "ONU-08");
    private static final List<String> SPLITTER_S3 = List.of(
            "ONU-09", "ONU-10", "ONU-11", "ONU-12", "ONU-13", "ONU-14", "ONU-15", "ONU-16");

    private final String scenarioId;
    private final Instant start;
    private final List<Event> events = new ArrayList<>();

    private OltSimulator(String scenarioId, Instant start) {
        this.scenarioId = scenarioId;
        this.start = start;
        emit(SPLITTER_S2, EventType.ONU_ONLINE, 0, 0);
        emit(SPLITTER_S3, EventType.ONU_ONLINE, 0, 0);
    }

    public static List<Event> branchLos() {
        var replay = new OltSimulator("branch-los", Instant.parse("2026-01-01T00:00:00Z"));
        replay.emit(SPLITTER_S2, EventType.ONU_LOS, 30, 5);
        replay.emit(SPLITTER_S2, EventType.ONU_ONLINE, 120, 1);
        return List.copyOf(replay.events);
    }

    public static List<Event> dyingGasp() {
        var replay = new OltSimulator("dying-gasp", Instant.parse("2026-01-01T01:00:00Z"));
        replay.emit(List.of(SPLITTER_S3.getFirst()), EventType.ONU_DYING_GASP, 30, 0);
        return List.copyOf(replay.events);
    }

    private void emit(List<String> onuIds, EventType type, long firstOffsetSeconds, long gapSeconds) {
        Instant eventTime = start.plusSeconds(firstOffsetSeconds);
        for (String onuId : onuIds) {
            String eventId = scenarioId + "-" + (events.size() + 1);
            events.add(new Event(eventId, PON_PORT, onuId, type, eventTime, eventTime.plusMillis(250)));
            eventTime = eventTime.plusSeconds(gapSeconds);
        }
    }

    static void printReplay(PrintStream output) {
        output.println("eventId,ponPort,onuId,eventType,eventTimestamp,receiveTimestamp");
        for (List<Event> scenario : List.of(branchLos(), dyingGasp())) {
            for (Event event : scenario) {
                output.printf("%s,%s,%s,%s,%s,%s%n", event.eventId(), event.ponPort(), event.onuId(),
                        event.eventType(), event.eventTimestamp(), event.receiveTimestamp());
            }
        }
    }

    public static void main(String[] args) {
        printReplay(System.out);
    }
}
