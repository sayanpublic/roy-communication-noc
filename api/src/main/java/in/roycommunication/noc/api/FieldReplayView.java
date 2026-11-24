package in.roycommunication.noc.api;

import in.roycommunication.noc.adapter.syrotech.SyrotechFieldReplay;
import in.roycommunication.noc.adapter.syrotech.SyrotechFieldReplay.LogRow;
import in.roycommunication.noc.adapter.syrotech.SyrotechFieldReplay.PowerEpisode;
import in.roycommunication.noc.adapter.syrotech.SyrotechFieldReplay.StatusObservation;
import in.roycommunication.noc.correlation.CorrelationEngine;
import in.roycommunication.noc.correlation.CorrelationPolicy;
import in.roycommunication.noc.correlation.FiberTopology;
import in.roycommunication.noc.correlation.Incident;

import java.util.List;

/** A single sampled PON. Only the native local times belong in the field response. */
record FieldReplayView(String ponPort, List<StatusObservation> onus, List<PowerEpisode> powerEpisodes,
                       List<LogRow> journal, long sharedLosIncidentCount) {
    static FieldReplayView from(String displayPort, SyrotechFieldReplay.Result capture) {
        var sampled = capture.statuses().stream().map(reading -> reading.source().onu()).toList();
        String pon = sampled.getFirst().pon();
        var inventory = sampled.stream()
                .map(onu -> new FiberTopology.Onu(onu.id(), onu.pon(), FiberTopology.Service.ACTIVE)).toList();
        var engine = new CorrelationEngine(new FiberTopology(pon, List.of(), inventory), CorrelationPolicy.defaults());
        capture.journal().stream().flatMap(entry -> entry.transition().stream()).forEach(engine::accept);
        long shared = engine.snapshot().incidents().stream().filter(incident -> incident.timeline().stream()
                .anyMatch(change -> change.basis() == Incident.Basis.INFERRED_SHARED_LOS)).count();

        return new FieldReplayView(displayPort, capture.statuses(), capture.powerEpisodes(),
                capture.journal().stream().map(SyrotechFieldReplay.LogObservation::source).toList(), shared);
    }
}
