package io.cafeai.sentinel.sink;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.cafeai.sentinel.incident.Incident;
import io.cafeai.sentinel.incident.IncidentEvent;
import io.cafeai.sentinel.investigate.Investigation;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Serialises incidents to a stable, compact JSON shape for the webhook and SSE
 * sinks. Hand-built maps rather than reflected over the records, so the wire
 * format doesn't move when an internal field does — {@code workload} is the
 * {@code Kind/name} string, timestamps are ISO-8601, and the tracker-internal
 * {@code investigatedReasons} / {@code lastErrorAt} are omitted.
 */
public final class IncidentJson {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private IncidentJson() {
    }

    /** One line of JSON for an incident lifecycle event. */
    public static String event(IncidentEvent event) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", event.type().name());
        m.put("incident", incidentMap(event.incident()));
        return write(m);
    }

    /** A JSON array of incident snapshots. */
    public static String incidents(Collection<Incident> incidents) {
        return write(incidents.stream().map(IncidentJson::incidentMap).toList());
    }

    private static Map<String, Object> incidentMap(Incident i) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", i.id());
        m.put("status", i.status().name());
        m.put("severity", i.severity().name());
        m.put("namespace", i.namespace());
        m.put("workload", i.workload().toString());
        m.put("firstSeen", i.firstSeen().toString());
        m.put("lastSeen", i.lastSeen().toString());
        m.put("signalCount", i.signalCount());
        m.put("reasons", List.copyOf(i.reasons()));
        m.put("affectedPods", List.copyOf(i.affectedPods()));
        m.put("evidence", i.evidence());
        if (i.investigation() != null) {
            m.put("investigation", investigationMap(i.investigation()));
        }
        return m;
    }

    private static Map<String, Object> investigationMap(Investigation inv) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("summary", inv.summary());
        m.put("category", inv.category() == null ? null : inv.category().name());
        m.put("likelyCause", inv.likelyCause());
        m.put("confidence", inv.confidence() == null ? null : inv.confidence().name());
        m.put("suggestedActions", inv.suggestedActions());
        m.put("relatedObjects", inv.relatedObjects());
        return m;
    }

    private static String write(Object value) {
        try {
            return MAPPER.writeValueAsString(value);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            return "{\"error\":\"incident serialization failed\"}";
        }
    }
}
