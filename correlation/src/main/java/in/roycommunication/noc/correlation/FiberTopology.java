package in.roycommunication.noc.correlation;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Optical connectivity and service inventory for one PON. Parent IDs point upstream. */
public final class FiberTopology {
    public enum Service { ACTIVE, MAINTENANCE, DECOMMISSIONED }
    public enum Kind { PON, SPLITTER }
    public record Splitter(String id, String parentId, String inputSegment) {}
    public record Onu(String id, String parentId, Service service) {}
    /** Known segment IDs run upstream to downstream. inputSegment is set for a single identified input. */
    public record Scope(Kind kind, String id, String inputSegment, List<String> candidateSegments) {
        public Scope {
            candidateSegments = List.copyOf(candidateSegments);
            if (inputSegment != null && !candidateSegments.equals(List.of(inputSegment))) {
                throw new IllegalArgumentException("A single input must match the sole candidate segment");
            }
        }

        public Scope(Kind kind, String id, String inputSegment) {
            this(kind, id, inputSegment, inputSegment == null ? List.of() : List.of(inputSegment));
        }
    }
    record Domain(Scope scope, Set<String> onus) {}

    private final Map<String, Onu> inventory = new LinkedHashMap<>();
    private final List<String> problems = new ArrayList<>();
    private final List<Domain> domains = new ArrayList<>();
    private final Scope observedPort;
    private final Domain port;

    public FiberTopology(String ponPort, List<Splitter> splitters, List<Onu> onus) {
        if (ponPort == null || ponPort.isBlank()) throw new IllegalArgumentException("Missing PON ID");
        Set<String> ids = new HashSet<>(Set.of(ponPort));
        Map<String, Splitter> byId = new LinkedHashMap<>();
        Map<String, Set<String>> descendants = new LinkedHashMap<>();
        for (Splitter splitter : splitters) {
            if (register(ids, splitter.id())) {
                byId.put(splitter.id(), splitter);
                descendants.put(splitter.id(), new LinkedHashSet<>());
            }
        }
        Map<String, Integer> depths = new LinkedHashMap<>();
        for (Splitter splitter : byId.values()) {
            depths.put(splitter.id(), path(splitter.id(), ponPort, byId).size());
        }
        for (Onu onu : onus) {
            if (!register(ids, onu.id())) continue;
            inventory.put(onu.id(), onu);
            if (onu.service() == null) problems.add("Missing service status for " + onu.id());
            for (String ancestor : path(onu.parentId(), ponPort, byId)) {
                descendants.get(ancestor).add(onu.id());
            }
        }
        Set<String> portOnus = Set.copyOf(inventory.keySet());
        observedPort = new Scope(Kind.PON, ponPort, null);
        if (!problems.isEmpty()) {
            port = new Domain(observedPort, portOnus);
            return;
        }

        // Inputs with identical physical descendants are one candidate domain, even in a long cascade.
        Map<Set<String>, List<Splitter>> chains = new LinkedHashMap<>();
        byId.values().stream().sorted(Comparator.comparingInt(s -> depths.get(s.id())))
                .forEach(splitter -> {
                    Set<String> members = Set.copyOf(descendants.get(splitter.id()));
                    if (!members.isEmpty()) chains.computeIfAbsent(members, key -> new ArrayList<>()).add(splitter);
                });
        port = new Domain(inferredScope(ponPort, Kind.PON, chains.getOrDefault(portOnus, List.of())), portOnus);
        chains.remove(portOnus);
        chains.forEach((members, chain) -> {
            // Retain the existing deepest splitter ID as the branch label.
            Scope scope = inferredScope(chain.getLast().id(), Kind.SPLITTER, chain);
            domains.add(new Domain(scope, members));
        });
        domains.sort(Comparator.comparingInt(domain -> domain.onus().size()));
        domains.add(port);
    }

    private static Scope inferredScope(String id, Kind kind, List<Splitter> chain) {
        List<String> segments = chain.stream().map(Splitter::inputSegment)
                .filter(segment -> segment != null && !segment.isBlank()).distinct().toList();
        // An unlabelled input still counts as an alternative to any named segment on the chain.
        String uniqueInput = kind == Kind.SPLITTER && chain.size() == 1 && !segments.isEmpty() ? segments.getFirst() : null;
        return new Scope(kind, id, uniqueInput, segments);
    }

    private boolean register(Set<String> ids, String id) {
        if (id != null && !id.isBlank() && ids.add(id)) return true;
        problems.add("Missing or duplicate topology ID: " + id);
        return false;
    }

    private List<String> path(String start, String root, Map<String, Splitter> splitters) {
        var visited = new LinkedHashSet<String>();
        String node = start;
        while (!Objects.equals(node, root)) {
            Splitter parent = splitters.get(node);
            if (parent == null || !visited.add(node)) {
                problems.add("Orphan or cycle on path from " + start + " at " + node);
                return List.of();
            }
            node = parent.parentId();
        }
        return List.copyOf(visited);
    }

    public String ponPort() { return port.scope().id(); }
    public List<String> problems() { return List.copyOf(problems); }
    Domain port() { return port; }
    Scope observedPort() { return observedPort; }
    List<Domain> domains() { return List.copyOf(domains); }
    boolean confinedToChild(Domain parent, Set<String> affected) {
        return domains.stream().anyMatch(child -> !child.equals(parent)
                && parent.onus().containsAll(child.onus()) && child.onus().containsAll(affected));
    }

    boolean inService(String id) {
        Onu onu = inventory.get(id);
        return onu != null && onu.service() == Service.ACTIVE;
    }
}
