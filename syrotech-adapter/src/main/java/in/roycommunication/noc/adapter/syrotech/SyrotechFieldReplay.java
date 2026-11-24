package in.roycommunication.noc.adapter.syrotech;

import in.roycommunication.noc.correlation.Alarm;
import in.roycommunication.noc.correlation.CorrelationEngine.OnuState;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;

/** Replays a single redacted portal capture. Source times are on the OLT's unverified local clock. */
public final class SyrotechFieldReplay {
    public record Onu(String pon, String id) {}
    public record LogRow(int tableRow, LocalDateTime oltLocalTime, Onu onu, String event, String diagnostic) {
        public LogRow {
            if (tableRow < 1) throw new IllegalArgumentException("Expected a positive portal row number");
            Objects.requireNonNull(oltLocalTime);
            Objects.requireNonNull(onu);
            Objects.requireNonNull(event);
        }
    }
    /** The portal can show N/A for either historical time. It supplies no observation time for this row. */
    public record StatusRow(Onu onu, String status, LocalDateTime lastRegister, LocalDateTime lastDeregister,
                            String lastDeregisterReason) {}
    public record LogObservation(LogRow source, Optional<Alarm> transition) {}
    public record StatusObservation(StatusRow source, OnuState state) {}
    public record PowerEpisode(LogRow dyingGasp, List<LogRow> followUps) {
        public PowerEpisode { followUps = List.copyOf(followUps); }
    }
    public record Result(List<LogObservation> journal, List<StatusObservation> statuses,
                         List<PowerEpisode> powerEpisodes) {
        public Result {
            journal = List.copyOf(journal);
            statuses = List.copyOf(statuses);
            powerEpisodes = List.copyOf(powerEpisodes);
        }
    }

    private static final String DYING_GASP = "ONU Dying Gasp";
    private final Duration timeoutWindow;
    private final Map<Onu, List<LogRow>> openPower = new HashMap<>();
    private final List<List<LogRow>> powerHistory = new ArrayList<>();

    private SyrotechFieldReplay(Duration timeoutWindow) {
        if (timeoutWindow.isNegative()) throw new IllegalArgumentException("Negative timeout association window");
        this.timeoutWindow = timeoutWindow;
    }

    public static Result replay(List<LogRow> log, List<StatusRow> statuses, Duration timeoutWindow) {
        var replay = new SyrotechFieldReplay(timeoutWindow);
        var journal = replay.readLog(log);
        var states = statuses.stream().map(row -> new StatusObservation(row, replay.currentState(row))).toList();
        var episodes = replay.powerHistory.stream()
                .map(rows -> new PowerEpisode(rows.getFirst(), rows.subList(1, rows.size()))).toList();
        return new Result(journal, states, episodes);
    }

    private List<LogObservation> readLog(List<LogRow> log) {
        // The displayed table is newest first. Its row numbers also settle ties within a second.
        var ordered = new TreeMap<Integer, LogRow>(Comparator.reverseOrder());
        for (LogRow row : log) {
            LogRow previous = ordered.putIfAbsent(row.tableRow(), row);
            if (previous != null && !previous.equals(row)) {
                throw new IllegalArgumentException("Conflicting entries for portal row " + row.tableRow());
            }
        }
        if (ordered.isEmpty()) return List.of();
        LocalDateTime origin = ordered.firstEntry().getValue().oltLocalTime();
        var journal = new ArrayList<LogObservation>();
        for (LogRow row : ordered.values()) {
            Alarm.Type type = observe(row);
            Alarm alarm = null;
            if (type != null) {
                // The correlation engine takes Instants. Only elapsed time is known for this capture.
                Instant replayTime = Instant.EPOCH.plus(Duration.between(origin, row.oltLocalTime()));
                alarm = new Alarm("field-log-" + row.tableRow(), row.onu().pon(), row.onu().id(),
                        type, replayTime, replayTime);
            }
            journal.add(new LogObservation(row, Optional.ofNullable(alarm)));
        }
        return journal;
    }

    private Alarm.Type observe(LogRow row) {
        List<LogRow> episode = openPower.get(row.onu());
        boolean nearGasp = episode != null && episode.stream().anyMatch(gasp -> withinGaspWindow(gasp, row));
        return switch (row.event()) {
            case DYING_GASP -> {
                if (nearGasp) {
                    episode.add(row);
                    yield null;
                }
                var opened = new ArrayList<>(List.of(row));
                openPower.put(row.onu(), opened);
                powerHistory.add(opened);
                yield Alarm.Type.ONU_DYING_GASP;
            }
            case "ONU Deregister" -> {
                if (nearGasp && ("MPCP TIMEOUT.".equals(row.diagnostic()) || "MPCP TIMEOUT".equals(row.diagnostic()))) {
                    episode.add(row);
                    yield null;
                }
                openPower.remove(row.onu());
                yield Alarm.Type.ONU_OFFLINE_UNKNOWN;
            }
            case "ONU Register", "ONU AUTH Success", "ONU Finish" -> {
                openPower.remove(row.onu());
                yield "ONU Finish".equals(row.event()) ? Alarm.Type.ONU_ONLINE : null;
            }
            default -> null;
        };
    }

    private boolean withinGaspWindow(LogRow gasp, LogRow row) {
        Duration elapsed = Duration.between(gasp.oltLocalTime(), row.oltLocalTime());
        return DYING_GASP.equals(gasp.event()) && !elapsed.isNegative() && elapsed.compareTo(timeoutWindow) <= 0;
    }

    private OnuState currentState(StatusRow row) {
        if ("Online".equals(row.status())) return OnuState.ONLINE;
        if (!"Offline".equals(row.status())) return OnuState.UNKNOWN;
        if ("Power Off".equals(row.lastDeregisterReason()) && row.lastDeregister() != null
                && (row.lastRegister() == null || row.lastRegister().isBefore(row.lastDeregister()))) {
            for (LogRow evidence : openPower.getOrDefault(row.onu(), List.of())) {
                if (DYING_GASP.equals(evidence.event()) && evidence.oltLocalTime().equals(row.lastDeregister())) {
                    return OnuState.DYING_GASP;
                }
            }
        }
        return OnuState.OFFLINE_UNKNOWN;
    }
}
