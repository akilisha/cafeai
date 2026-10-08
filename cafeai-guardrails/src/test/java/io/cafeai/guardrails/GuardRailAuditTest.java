package io.cafeai.guardrails;

import io.cafeai.core.CafeAI;
import io.cafeai.core.audit.AuditEvent;
import io.cafeai.core.audit.AuditSink;
import io.cafeai.core.guardrails.GuardRail;
import io.cafeai.core.identity.Identity;
import io.cafeai.core.routing.Request;
import io.cafeai.core.routing.Response;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@DisplayName("A guardrail used as middleware records its flags in the app's audit trail")
class GuardRailAuditTest {

    @Test @DisplayName("a blocked request is a REQUEST flag naming the caller and the guardrail's action")
    void blockedRequestIsAudited() {
        List<AuditEvent> recorded = new ArrayList<>();
        AuditSink sink = recorded::add;
        CafeAI app = mock(CafeAI.class);
        when(app.audit()).thenReturn(sink);

        Identity alice = Identity.builder("https://issuer.example.com", "alice")
                .expiresAt(Instant.now().plusSeconds(3600)).build();
        Request req = mock(Request.class);
        when(req.app()).thenReturn(app);
        when(req.identity()).thenReturn(Optional.of(alice));
        when(req.method()).thenReturn("POST");
        when(req.attribute("_routePattern")).thenReturn(null);
        when(req.bodyText()).thenReturn("My email is user@example.com");
        Response res = mock(Response.class, RETURNS_SELF);

        GuardRail pii = GuardRail.pii();
        pii.handle(req, res, () -> { });

        assertThat(recorded).hasSize(1);
        var flag = (AuditEvent.GuardrailFlag) recorded.get(0);
        assertThat(flag.caller()).isEqualTo(alice.key());
        assertThat(flag.route()).isEqualTo("POST (unmatched)");   // a filter runs before routing
        assertThat(flag.guardrail()).isEqualTo(pii.name());
        assertThat(flag.stage()).isEqualTo(AuditEvent.Stage.REQUEST);
        assertThat(flag.action()).isEqualTo(pii.action());
    }

    @Test @DisplayName("a request with no app (a test double) is screened as before, with nothing recorded")
    void noAppNoAudit() {
        Request req = mock(Request.class);
        when(req.bodyText()).thenReturn("My email is user@example.com");
        Response res = mock(Response.class, RETURNS_SELF);

        GuardRail.pii().handle(req, res, () -> { });

        verify(res).status(400);
    }
}
