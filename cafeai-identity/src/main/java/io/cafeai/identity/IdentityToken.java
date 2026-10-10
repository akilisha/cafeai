package io.cafeai.identity;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Where a workload's identity token comes from: a JWT from the organisation's own identity
 * provider, presented to another service to prove who the workload is
 * ({@link AnthropicFederation}).
 *
 * <p>{@link #get()} is asked for a token at every exchange, and should hand out a fresh one:
 * a service that treats a token's {@code jti} as single-use (Anthropic does, by default) refuses
 * one it has seen before.
 */
@FunctionalInterface
public interface IdentityToken {

    /** A current identity token (a JWT). */
    String get();

    /**
     * Read from {@code file} at every exchange: for a platform that keeps a rotated token on disk
     * (a Kubernetes projected service-account token, a cloud agent's token file).
     */
    static IdentityToken file(Path file) {
        Objects.requireNonNull(file, "file");
        return () -> {
            try {
                return Files.readString(file).trim();
            } catch (IOException e) {
                throw new IdentityException("Could not read the identity token at " + file, e);
            }
        };
    }

    /** Read from the environment variable {@code name} at every exchange. */
    static IdentityToken env(String name) {
        Objects.requireNonNull(name, "name");
        return () -> {
            String value = System.getenv(name);
            if (value == null || value.isBlank()) throw new IdentityException("No identity token in $" + name);
            return value.trim();
        };
    }

    /**
     * A new token from {@code issuer} for every exchange, as the app itself (the client
     * credentials grant): for an app whose identity lives at the organisation's issuer (Entra
     * ID, Okta, Keycloak). Never cached, so each exchange presents a token not seen before.
     */
    static FromIssuer clientCredentials(Issuer issuer, String clientId, String clientSecret) {
        return new FromIssuer(TokenEndpoint.confidential(issuer, clientId, clientSecret));
    }

    /** {@link #clientCredentials}: a new client credentials token per exchange. */
    final class FromIssuer implements IdentityToken {
        private final TokenEndpoint endpoint;
        private String audience;
        private String scope;

        FromIssuer(TokenEndpoint endpoint) {
            this.endpoint = endpoint;
        }

        /** The audience to ask for, when the issuer expects one ({@code audience}). */
        public FromIssuer audience(String audience) {
            this.audience = audience;
            return this;
        }

        /** Scopes to ask for (Entra: {@code api://<app>/.default}). */
        public FromIssuer scope(String... scopes) {
            this.scope = String.join(" ", scopes);
            return this;
        }

        @Override
        public String get() {
            Map<String, String> form = new LinkedHashMap<>();
            form.put("grant_type", "client_credentials");
            if (audience != null) form.put("audience", audience);
            if (scope != null) form.put("scope", scope);
            return endpoint.request(form, Instant.now()).value();
        }
    }
}
