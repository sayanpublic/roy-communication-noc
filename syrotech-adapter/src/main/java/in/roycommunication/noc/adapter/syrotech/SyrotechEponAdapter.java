package in.roycommunication.noc.adapter.syrotech;

import in.roycommunication.noc.correlation.Alarm;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import static in.roycommunication.noc.correlation.Alarm.Type.*;

/** Normalizes one finite, synthetic status replay for a single OLT. */
public final class SyrotechEponAdapter {
    public record Observation(OnuStatusSnapshot source, Alarm alarm) {}
    private record OnuKey(String ponPort, String onuId) {}
    private record Outage(OnuKey onu, Instant firstOfflineAt) {}

    private record ClassifiedRow(OnuStatusSnapshot row, Alarm.Type type, Outage supportedOutage) {
        Observation resolve(Map<Outage, Alarm.Type> causes) {
            var resolved = supportedOutage == null ? type : causes.get(supportedOutage);
            return new Observation(row, new Alarm(row.eventId(), row.ponPort(), row.onuId(), resolved,
                    row.observedAt(), row.receivedAt()));
        }
    }

    // At the same sample time, an online reading closes the previous outage first.
    private static final Comparator<OnuStatusSnapshot> SAMPLE_ORDER = Comparator
            .comparing(OnuStatusSnapshot::observedAt).thenComparing(OnuStatusSnapshot::status)
            .thenComparing(OnuStatusSnapshot::eventId).thenComparing(OnuStatusSnapshot::receivedAt);
    private final Duration maxReasonAge;

    public SyrotechEponAdapter(Duration maxReasonAge) {
        this.maxReasonAge = Objects.requireNonNull(maxReasonAge, "maxReasonAge");
        if (maxReasonAge.isNegative()) throw new IllegalArgumentException("Negative reason age limit");
    }

    public List<Observation> normalize(List<OnuStatusSnapshot> snapshots) {
        var histories = new HashMap<OnuKey, OnuHistory>();
        var causes = new HashMap<Outage, Alarm.Type>();
        var readings = new ArrayList<ClassifiedRow>(snapshots.size());
        for (var row : snapshots.stream().sorted(SAMPLE_ORDER).toList()) {
            var key = new OnuKey(row.ponPort(), row.onuId());
            var history = histories.computeIfAbsent(key, ignored -> new OnuHistory());
            var type = history.classify(row, maxReasonAge);
            Outage supportedOutage = null;
            if (type == ONU_LOS || type == ONU_DYING_GASP) {
                supportedOutage = new Outage(key, history.firstOfflineAt);
                causes.merge(supportedOutage, type, (first, next) -> first == next ? first : ONU_OFFLINE_UNKNOWN);
            }
            readings.add(new ClassifiedRow(row, type, supportedOutage));
        }
        // Settle competing causes before publishing any specific alarm for that offline interval.
        return readings.stream().map(reading -> reading.resolve(causes)).toList();
    }

    private static final class OnuHistory {
        private final Set<Instant> futureDatedReports = new HashSet<>();
        private Instant lastOnlineAt;
        private Instant firstOfflineAt;

        Alarm.Type classify(OnuStatusSnapshot row, Duration maxAge) {
            var reason = row.lastDownReason();
            Instant disconnectAt = reason == null ? null : reason.occurredAt();
            if (disconnectAt != null && disconnectAt.isAfter(row.observedAt())) {
                futureDatedReports.add(disconnectAt);
            }
            if (row.status() == OnuStatusSnapshot.Status.ONLINE) {
                lastOnlineAt = row.observedAt();
                firstOfflineAt = null;
                return ONU_ONLINE;
            }
            if (firstOfflineAt == null) firstOfflineAt = row.observedAt();
            if (disconnectAt == null || reason.text() == null || futureDatedReports.contains(disconnectAt)
                    || !belongsToCurrentOutage(disconnectAt)
                    || Duration.between(disconnectAt, row.observedAt()).compareTo(maxAge) > 0) {
                return ONU_OFFLINE_UNKNOWN;
            }
            return switch (reason.text().strip().toUpperCase(Locale.ROOT)) {
                case "LOS", "LOSS OF SIGNAL" -> ONU_LOS;
                case "DYING GASP" -> ONU_DYING_GASP;
                default -> ONU_OFFLINE_UNKNOWN;
            };
        }

        private boolean belongsToCurrentOutage(Instant disconnectAt) {
            if (lastOnlineAt == null) return disconnectAt.equals(firstOfflineAt);
            return disconnectAt.isAfter(lastOnlineAt) && !disconnectAt.isAfter(firstOfflineAt);
        }
    }
}
