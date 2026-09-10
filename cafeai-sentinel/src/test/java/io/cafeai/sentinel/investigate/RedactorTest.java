package io.cafeai.sentinel.investigate;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class RedactorTest {

    private final Redactor redactor = Redactor.enabled();

    @Test
    void redactsBearerAndBasicAuthHeaders() {
        assertThat(redactor.redact("Authorization: Bearer sk-abc123def456"))
                .isEqualTo("Authorization: Bearer [REDACTED]");
        assertThat(redactor.redact("authorization=Basic dXNlcjpwYXNzd29yZA=="))
                .contains("[REDACTED]").doesNotContain("dXNlcjpwYXNz");
    }

    @Test
    void redactsCredentialsInAUrl() {
        assertThat(redactor.redact("jdbc:postgresql://svc:S3cr3t@db.internal:5432/app"))
                .isEqualTo("jdbc:postgresql://[REDACTED]@db.internal:5432/app");
    }

    @Test
    void redactsSecretKeyValuePairs() {
        assertThat(redactor.redact("DB_PASSWORD=hunter2")).isEqualTo("DB_PASSWORD=[REDACTED]");
        assertThat(redactor.redact("api_key: \"abcdef123456\"")).isEqualTo("api_key=[REDACTED]");
        assertThat(redactor.redact("client-secret = zzz")).isEqualTo("client-secret=[REDACTED]");
    }

    @Test
    void redactsAwsKeysJwtsAndPrivateKeys() {
        assertThat(redactor.redact("key AKIAIOSFODNN7EXAMPLE here")).contains("[REDACTED_AWS_KEY]");
        assertThat(redactor.redact("token eyJhbGci.eyJzdWIi.SflKxwRJ done")).contains("[REDACTED_TOKEN]");
        String pem = "-----BEGIN RSA PRIVATE KEY-----\nMIIEabc\n-----END RSA PRIVATE KEY-----";
        assertThat(redactor.redact("leaked:\n" + pem)).contains("[REDACTED_PRIVATE_KEY]")
                .doesNotContain("MIIEabc");
    }

    @Test
    void redactsPiiViaGuardrails() {
        String scrubbed = redactor.redact("contact ada@example.com or 555-867-5309");
        assertThat(scrubbed).doesNotContain("ada@example.com").doesNotContain("555-867-5309");
    }

    @Test
    void disabledRedactorIsPassThrough() {
        String secret = "DB_PASSWORD=hunter2 ada@example.com";
        assertThat(Redactor.disabled().redact(secret)).isEqualTo(secret);
    }

    @Test
    void redactsEveryFreeTextFieldOfAnInvestigation() {
        Investigation raw = new Investigation(
                "app logs show DB_PASSWORD=hunter2 on startup",
                CauseCategory.CONFIG,
                "connection string jdbc:mysql://u:p@host is wrong",
                Confidence.MEDIUM,
                List.of("rotate the key AKIAIOSFODNN7EXAMPLE"),
                List.of("Secret/db-creds"));

        Investigation clean = redactor.redact(raw);

        assertThat(clean.summary()).doesNotContain("hunter2");
        assertThat(clean.likelyCause()).contains("[REDACTED]@host");
        assertThat(clean.suggestedActions().get(0)).contains("[REDACTED_AWS_KEY]");
        assertThat(clean.category()).isEqualTo(CauseCategory.CONFIG);
        assertThat(clean.relatedObjects()).containsExactly("Secret/db-creds");
    }

    @Test
    void nullAndEmptyAreSafe() {
        assertThat(redactor.redact((String) null)).isNull();
        assertThat(redactor.redact("")).isEmpty();
    }
}
