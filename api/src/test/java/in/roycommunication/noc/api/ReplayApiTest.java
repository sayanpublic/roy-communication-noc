package in.roycommunication.noc.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.web.ServerProperties;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ReplayApiTest {
    @LocalServerPort int port;
    @Autowired TestRestTemplate http;
    @Autowired ServerProperties server;

    @Test
    void openS2LinksSixObservedLossesToOneInferredIncident() {
        JsonNode view = get("/s2-open", JsonNode.class);
        assertEquals("s2-open", view.required("viewId").textValue());
        assertEquals("2026-01-01T00:00:55.250Z", view.required("replayTimestamp").textValue());
        assertEquals("PON-03", view.at("/observed/ponPort").textValue());
        assertEquals("REACHABLE", view.at("/observed/state").textValue());
        JsonNode onus = view.at("/observed/onus");
        assertEquals(16, onus.size());
        for (int i = 1; i <= 16; i++) {
            String id = String.format(Locale.ROOT, "ONU-%02d", i);
            assertEquals(i <= 6 ? "LOS" : "ONLINE", onus.required(id).required("state").textValue());
        }

        JsonNode incident = onlyIncident(view);
        assertEquals("PON-03/S2/1", incident.required("id").textValue());
        assertEquals("OPEN", incident.required("status").textValue());
        assertEquals(1, incident.required("timeline").size());
        JsonNode change = incident.at("/timeline/0");
        assertEquals("2026-01-01T00:00:55Z", change.required("at").textValue());
        assertEquals("INFERRED_SHARED_LOS", change.required("basis").textValue());
        assertEquals("S2", change.at("/scope/id").textValue());
        assertEquals(JsonNodeFactory.instance.arrayNode().add("F2"), change.at("/scope/candidateSegments"));
        assertEquals(6, change.required("affectedCount").intValue());
        assertEquals(8, change.required("eligibleCount").intValue());
        JsonNode evidence = change.required("evidenceIds");
        JsonNode alarms = view.at("/observed/alarms");
        assertEquals(6, evidence.size());
        assertEquals(22, alarms.size());
        for (int i = 0; i < evidence.size(); i++) {
            JsonNode alarm = alarms.required(16 + i);
            assertEquals(alarm.required("eventId"), evidence.required(i));
            assertEquals("ONU_LOS", alarm.required("type").textValue());
            assertEquals(alarm, onus.required(alarm.required("onuId").textValue()).required("lastObservation"));
        }
        assertEquals(change.required("at"), alarms.required(21).required("eventTimestamp"));
        assertEquals(view.required("replayTimestamp"), alarms.required(21).required("receiveTimestamp"));
    }

    @Test
    void resolvedS2IncludesPeakImpactAndStableRecovery() {
        JsonNode view = get("/s2-resolved", JsonNode.class);
        assertEquals("2026-01-01T00:02:37Z", view.required("replayTimestamp").textValue());
        assertEquals(32, view.at("/observed/alarms").size());
        JsonNode onus = view.at("/observed/onus");
        assertEquals(16, onus.size());
        for (JsonNode onu : onus) assertEquals("ONLINE", onu.required("state").textValue());
        JsonNode incident = onlyIncident(view);
        assertEquals("PON-03/S2/1", incident.required("id").textValue());
        assertEquals("RESOLVED", incident.required("status").textValue());
        JsonNode timeline = incident.required("timeline");
        assertEquals(6, timeline.required(0).required("affectedCount").intValue());
        JsonNode peak = timeline.required(2);
        assertEquals("2026-01-01T00:01:05Z", peak.required("at").textValue());
        assertEquals(8, peak.required("affectedCount").intValue());
        JsonNode recovering = timeline.required(timeline.size() - 2);
        assertEquals("RECOVERING", recovering.required("status").textValue());
        assertEquals("2026-01-01T00:02:07Z", recovering.required("at").textValue());
        JsonNode resolved = timeline.required(timeline.size() - 1);
        assertEquals("RESOLVED", resolved.required("status").textValue());
        assertEquals(view.required("replayTimestamp"), resolved.required("at"));
        assertEquals(0, resolved.required("affectedCount").intValue());
        assertEquals(8, resolved.required("eligibleCount").intValue());
    }

    @Test
    void dyingGaspIsObservedOnOneOnuWithoutASharedIncident() {
        JsonNode view = get("/dying-gasp", JsonNode.class);
        assertEquals("2026-01-01T01:00:30.250Z", view.required("replayTimestamp").textValue());
        assertEquals(JsonNodeFactory.instance.arrayNode(), view.required("incidents"));
        JsonNode onus = view.at("/observed/onus");
        assertEquals(16, onus.size());
        onus.properties().forEach(entry -> assertEquals(entry.getKey().equals("ONU-09") ? "DYING_GASP" : "ONLINE",
                entry.getValue().required("state").textValue()));
        assertEquals("ONU_DYING_GASP", onus.at("/ONU-09/lastObservation/type").textValue());
    }

    @Test
    void cascadeReportsBothCandidateSegmentsOnOneIncident() {
        JsonNode view = get("/cascade-open", JsonNode.class);
        JsonNode incident = onlyIncident(view);
        assertEquals("OPEN", incident.required("status").textValue());
        JsonNode change = incident.at("/timeline/0");
        assertEquals("INFERRED_SHARED_LOS", change.required("basis").textValue());
        assertEquals("S4", change.at("/scope/id").textValue());
        assertEquals(JsonNodeFactory.instance.arrayNode().add("F2").add("F4"), change.at("/scope/candidateSegments"));
        assertTrue(change.required("scope").required("inputSegment").isNull());
        assertEquals("ONLINE", view.at("/observed/onus/ONU-09/state").textValue());
    }

    @Test
    void delayedLosHasSeparateOnsetAndConfirmationTimes() {
        JsonNode pending = get("/delayed-los-pending", JsonNode.class);
        assertTrue(pending.required("incidents").isEmpty());
        assertEquals("OFFLINE_UNKNOWN", pending.at("/observed/onus/ONU-01/state").textValue());

        JsonNode confirmed = get("/delayed-los-confirmed", JsonNode.class);
        JsonNode opening = onlyIncident(confirmed).at("/timeline/0");
        assertEquals("2026-01-01T00:00:12Z", opening.required("at").textValue());
        assertEquals("2026-01-01T00:00:24.250Z", confirmed.required("replayTimestamp").textValue());
        assertEquals(3, opening.required("affectedCount").intValue());
        assertEquals(4, opening.required("eligibleCount").intValue());
        assertEquals("S2", confirmed.at("/topology/onuParents/ONU-01").textValue());
        assertEquals("S3", confirmed.at("/topology/onuParents/ONU-09").textValue());
    }

    @Test
    void servesDashboardAtLoopbackRoot() {
        var response = http.getForEntity("http://127.0.0.1:" + port + "/", String.class);
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertTrue(MediaType.TEXT_HTML.isCompatibleWith(response.getHeaders().getContentType()));
        assertTrue(Objects.requireNonNull(response.getBody()).contains("Network replay"));
    }

    @Test
    void interleavedGetsReturnIdenticalBodiesForEveryListedView() {
        List<String> ids = Arrays.asList(get("", String[].class));
        assertEquals(List.of("s2-open", "s2-resolved", "dying-gasp", "cascade-open",
                "delayed-los-pending", "delayed-los-confirmed"), ids);
        var bodies = new HashMap<String, String>();
        for (String id : ids) bodies.put(id, get("/" + id, String.class));
        for (String id : ids.reversed()) assertEquals(bodies.get(id), get("/" + id, String.class));
    }

    @Test
    void apiUsesLoopbackAndRejectsUnknownViewsAndWriteMethods() {
        assertEquals("127.0.0.1", server.getAddress().getHostAddress());
        assertEquals(HttpStatus.NOT_FOUND, http.getForEntity(url("/absent"), String.class).getStatusCode());
        for (HttpMethod method : List.of(HttpMethod.POST, HttpMethod.PUT, HttpMethod.DELETE)) {
            var response = http.exchange(url("/s2-open"), method, HttpEntity.EMPTY, String.class);
            assertEquals(HttpStatus.METHOD_NOT_ALLOWED, response.getStatusCode());
        }
    }

    private static JsonNode onlyIncident(JsonNode view) {
        JsonNode incidents = view.required("incidents");
        assertEquals(1, incidents.size());
        return incidents.required(0);
    }

    private <T> T get(String suffix, Class<T> type) {
        var response = http.getForEntity(url(suffix), type);
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertTrue(MediaType.APPLICATION_JSON.isCompatibleWith(response.getHeaders().getContentType()));
        return Objects.requireNonNull(response.getBody());
    }

    private String url(String suffix) {
        return "http://127.0.0.1:" + port + "/api/replays" + suffix;
    }
}
