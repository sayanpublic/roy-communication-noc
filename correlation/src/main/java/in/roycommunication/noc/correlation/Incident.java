package in.roycommunication.noc.correlation;

import java.time.Instant;
import java.util.List;
import java.util.Set;

public record Incident(String id, List<Change> timeline, String supersededBy) {
    public enum Basis { INFERRED_SHARED_LOS, OBSERVED_PORT_DOWN }
    public enum Status { OPEN, RECOVERING, RESOLVED, SUPERSEDED }

    /** Counts and scope as of this change. Evidence IDs refer to original normalized alarms. */
    public record Change(Instant at, FiberTopology.Scope scope, Basis basis, Status status,
                         Set<String> affected, Set<String> eligible, List<String> evidenceIds) {
        public Change {
            affected = Set.copyOf(affected);
            eligible = Set.copyOf(eligible);
            evidenceIds = List.copyOf(evidenceIds);
        }
    }

    public Incident { timeline = List.copyOf(timeline); }
    public Change latest() { return timeline.getLast(); }
    public boolean active() { return latest().status() == Status.OPEN || latest().status() == Status.RECOVERING; }
}
