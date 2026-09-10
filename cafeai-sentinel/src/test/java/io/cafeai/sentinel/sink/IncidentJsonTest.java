package io.cafeai.sentinel.sink;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.cafeai.sentinel.incident.IncidentEvent;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class IncidentJsonTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void eventJsonHasTheStableShape() throws Exception {
        String json = IncidentJson.event(SinkTestSupport.event(IncidentEvent.Type.INVESTIGATED));
        JsonNode root = mapper.readTree(json);

        assertThat(root.get("type").asText()).isEqualTo("INVESTIGATED");
        JsonNode inc = root.get("incident");
        assertThat(inc.get("id").asText()).isEqualTo("inc-abc12345");
        assertThat(inc.get("workload").asText()).isEqualTo("Deployment/web");
        assertThat(inc.get("severity").asText()).isEqualTo("ERROR");
        assertThat(inc.get("firstSeen").asText()).isEqualTo("2026-09-10T12:00:00Z");
        assertThat(inc.get("reasons")).hasSize(2);
        assertThat(inc.has("investigatedReasons")).isFalse();
        assertThat(inc.get("investigation").get("category").asText()).isEqualTo("RESOURCES");
        assertThat(inc.get("investigation").get("suggestedActions").get(0).asText())
                .contains("128Mi");
    }

    @Test
    void incidentsJsonIsAnArray() throws Exception {
        String json = IncidentJson.incidents(List.of(SinkTestSupport.incident()));
        JsonNode root = mapper.readTree(json);

        assertThat(root.isArray()).isTrue();
        assertThat(root.get(0).get("id").asText()).isEqualTo("inc-abc12345");
    }

    @Test
    void oneLinePerEvent() {
        String json = IncidentJson.event(SinkTestSupport.event(IncidentEvent.Type.OPENED));
        assertThat(json).doesNotContain("\n");
    }
}
