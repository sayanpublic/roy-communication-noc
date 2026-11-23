package in.roycommunication.noc.correlation;

import java.time.Instant;
import java.util.Objects;

/** A normalized observation. OLT visibility signals can be delivered to each of its PON engines. */
public record Alarm(String eventId, String ponPort, String onuId, Type type,
                    Instant eventTimestamp, Instant receiveTimestamp) {
    public enum Type {
        ONU_ONLINE, ONU_LOS, ONU_DYING_GASP, ONU_OFFLINE_UNKNOWN,
        PON_PORT_DOWN, PON_PORT_UP, OLT_UNREACHABLE, OLT_REACHABLE
    }

    public Alarm {
        if (eventId == null || eventId.isBlank() || ponPort == null || ponPort.isBlank()) {
            throw new IllegalArgumentException("An alarm needs an event ID and PON port");
        }
        Objects.requireNonNull(type);
        Objects.requireNonNull(eventTimestamp);
        Objects.requireNonNull(receiveTimestamp);
        boolean onuAlarm = switch (type) {
            case ONU_ONLINE, ONU_LOS, ONU_DYING_GASP, ONU_OFFLINE_UNKNOWN -> true;
            default -> false;
        };
        if (onuAlarm ? onuId == null || onuId.isBlank() : onuId != null) {
            throw new IllegalArgumentException("Invalid ONU ID for " + type);
        }
    }
}
