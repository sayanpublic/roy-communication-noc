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
    public record Scope(Kind kind, String id, String inputSegment) {}
    record Domain(Scope scope, Set<String> onus) {}

    private final Map<String, Onu> inventory = new LinkedHashMap<>();
    private final List<String> problems = new ArrayList<>();
    private final List<Domain> domains = new ArrayList<>();
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
        port = new Domain(new Scope(Kind.PON, ponPort, null), Set.copyOf(inventory.keySet()));
        if (!problems.isEmpty()) return;

        // Unary cascades have the same observable footprint. Keep the deepest proper subtree.
        Set<Set<String>> footprints = new HashSet<>();
        footprints.add(port.onus());
        byId.values().stream().sorted(Comparator.comparingInt((Splitter s) -> depths.get(s.id())).reversed())
                .forEach(splitter -> {
                    Set<String> members = Set.copyOf(descendants.get(splitter.id()));
                    if (!members.isEmpty() && footprints.add(members)) {
                        domains.add(new Domain(new Scope(Kind.SPLITTER, splitter.id(), splitter.inputSegment()), members));
                    }
                });
        domains.sort(Comparator.comparingInt(domain -> domain.onus().size()));
        domains.add(port);
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
