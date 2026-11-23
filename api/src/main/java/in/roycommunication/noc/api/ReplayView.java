package in.roycommunication.noc.api;

import in.roycommunication.noc.correlation.Alarm;
import in.roycommunication.noc.correlation.CorrelationEngine;
import in.roycommunication.noc.correlation.FiberTopology;
import in.roycommunication.noc.correlation.Incident;

import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

record ReplayView(String viewId, Instant replayTimestamp, ObservedPon observed, Topology topology, List<IncidentView> incidents) {
    record Topology(List<FiberTopology.Splitter> splitters, Map<String, String> onuParents) {}

    record ObservedPon(String ponPort, CorrelationEngine.PortState state,
                       Map<String, CorrelationEngine.OnuReading> onus, List<Alarm> alarms) {}

    record IncidentView(String id, Incident.Status status, String supersededBy, List<Change> timeline) {
        static IncidentView from(Incident incident) {
            return new IncidentView(incident.id(), incident.latest().status(), incident.supersededBy(),
                    incident.timeline().stream().map(Change::from).toList());
        }
    }

    record Change(Instant at, Incident.Status status, Incident.Basis basis, FiberTopology.Scope scope,
                  int affectedCount, int eligibleCount, List<String> affectedOnus, List<String> eligibleOnus,
                  List<String> evidenceIds) {
        static Change from(Incident.Change change) {
            return new Change(change.at(), change.status(), change.basis(), change.scope(),
                    change.affected().size(), change.eligible().size(),
                    change.affected().stream().sorted().toList(), change.eligible().stream().sorted().toList(),
                    change.evidenceIds());
        }
    }

    static ReplayView from(String viewId, String ponPort, CorrelationEngine.Snapshot snapshot, Topology topology) {
        var observed = new ObservedPon(ponPort, snapshot.portState(),
                Collections.unmodifiableSortedMap(new TreeMap<>(snapshot.onus())), snapshot.observations());
        return new ReplayView(viewId, snapshot.asOf(), observed, topology,
                snapshot.incidents().stream().map(IncidentView::from).toList());
    }
}
