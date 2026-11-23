package in.roycommunication.noc.correlation;

import java.time.Duration;

public record CorrelationPolicy(Duration window, int minimumOnus, int minimumPercent,
                                Duration freshness, Duration stableRecovery, Duration maxDeliveryLag) {
    public static CorrelationPolicy defaults() {
        return new CorrelationPolicy(Duration.ofSeconds(90), 3, 75, Duration.ofMinutes(5),
                Duration.ofSeconds(30), Duration.ofSeconds(90));
    }

    public CorrelationPolicy {
        if (!window.isPositive() || !freshness.isPositive() || stableRecovery.isNegative()
                || maxDeliveryLag.isNegative() || minimumOnus < 1 || minimumPercent < 1 || minimumPercent > 100) {
            throw new IllegalArgumentException("Invalid correlation thresholds or durations");
        }
    }

    boolean qualifies(int affected, int eligible) {
        return affected >= minimumOnus && 100L * affected >= (long) minimumPercent * eligible;
    }
}
