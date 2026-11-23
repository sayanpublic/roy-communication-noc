package in.roycommunication.noc.adapter.syrotech;

import java.time.Instant;
import java.util.Objects;

/** One synthetic status row. The event ID identifies the row across deliveries. */
public record OnuStatusSnapshot(String eventId, String ponPort, String onuId, Status status,
                                Instant observedAt, Instant receivedAt, LastDownReason lastDownReason) {
    public enum Status { ONLINE, OFFLINE }

    /** Optional source evidence. occurredAt is the disconnect time associated with this particular reason. */
    public record LastDownReason(String text, Instant occurredAt) {}

    public OnuStatusSnapshot {
        if (eventId == null || eventId.isBlank() || ponPort == null || ponPort.isBlank()
                || onuId == null || onuId.isBlank()) {
            throw new IllegalArgumentException("A snapshot needs a row ID, PON port and ONU ID");
        }
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(observedAt, "observedAt");
        Objects.requireNonNull(receivedAt, "receivedAt");
    }
}
