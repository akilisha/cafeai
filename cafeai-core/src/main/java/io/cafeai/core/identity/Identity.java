package io.cafeai.core.identity;

import io.cafeai.core.internal.CurrentRequest;
import io.cafeai.core.routing.Request;

import java.time.Instant;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Who a request is for: a caller whose token an OpenID Connect issuer signed and
 * {@code cafeai-identity} verified.
 *
 * <p>An identity is keyed by its {@linkplain #issuer() issuer} and {@linkplain #subject()
 * subject} together; a subject is only unique within its issuer. Scopes, groups, roles and
 * entitlements are read from the token's standard claims (RFC 9068) and say what the issuer
 * granted. CafeAI checks them where asked but decides no policy of its own.
 *
 * <pre>{@code
 *   app.filter(Auth.bearer(issuer, "orders-api"));
 *   app.get("/orders", (req, res, next) -> {
 *       Identity who = req.identity().orElseThrow();
 *       res.json(orders.ownedBy(who.subject()));
 *   });
 * }</pre>
 *
 * <p>Immutable. {@link #toString()} names the issuer and subject only, never the claims.
 */
public final class Identity {

    private final String issuer;
    private final String subject;
    private final String name;
    private final Instant expiresAt;
    private final Set<String> scopes;
    private final Set<String> groups;
    private final Set<String> roles;
    private final Set<String> entitlements;
    private final Map<String, Object> claims;

    private Identity(Builder b) {
        this.issuer = b.issuer;
        this.subject = b.subject;
        this.name = b.name;
        this.expiresAt = b.expiresAt;
        this.scopes = Set.copyOf(b.scopes);
        this.groups = Set.copyOf(b.groups);
        this.roles = Set.copyOf(b.roles);
        this.entitlements = Set.copyOf(b.entitlements);
        this.claims = Collections.unmodifiableMap(new LinkedHashMap<>(b.claims));
    }

    /**
     * The identity of the request this thread is working for, or empty when there is none: an
     * anonymous request, or work with no request behind it (startup, a scheduled job). Lets code
     * below a handler (a model call, a tool) find out who it is working for.
     */
    public static Optional<Identity> current() {
        return CurrentRequest.get().flatMap(Request::identity);
    }

    /** Starts an identity. Used by {@code cafeai-identity} and by tests. */
    public static Builder builder(String issuer, String subject) {
        return new Builder(issuer, subject);
    }

    /** The issuer that signed the token ({@code iss}). */
    public String issuer() { return issuer; }

    /** The caller, unique within its issuer ({@code sub}). */
    public String subject() { return subject; }

    /** A display name, when the token carries one ({@code name} or {@code preferred_username}). */
    public Optional<String> name() { return Optional.ofNullable(name); }

    /** When the token stops being valid ({@code exp}). */
    public Instant expiresAt() { return expiresAt; }

    /** Granted scopes ({@code scope}, space-separated in the token). */
    public Set<String> scopes() { return scopes; }

    /** Groups ({@code groups}, RFC 9068). */
    public Set<String> groups() { return groups; }

    /** Roles ({@code roles}, RFC 9068). */
    public Set<String> roles() { return roles; }

    /** Entitlements ({@code entitlements}, RFC 9068). */
    public Set<String> entitlements() { return entitlements; }

    /**
     * Every claim in the token, as strings, numbers, booleans, lists and maps. Unmodifiable.
     * Claims can be personal data; don't log them wholesale.
     */
    public Map<String, Object> claims() { return claims; }

    /** One claim, or empty. */
    public Optional<Object> claim(String name) { return Optional.ofNullable(claims.get(name)); }

    public boolean hasScope(String scope)             { return scopes.contains(scope); }
    public boolean inGroup(String group)              { return groups.contains(group); }
    public boolean hasRole(String role)               { return roles.contains(role); }
    public boolean hasEntitlement(String entitlement) { return entitlements.contains(entitlement); }

    /** True once {@link #expiresAt()} has passed. */
    public boolean expired(Instant now) { return !now.isBefore(expiresAt); }

    /**
     * Issuer and subject together: the key for anything held per caller (usage, conversation
     * memory, audit). Two identities with the same key are the same caller, whatever else differs.
     */
    public Key key() { return new Key(issuer, subject); }

    /** The issuer-and-subject pair that identifies a caller. */
    public record Key(String issuer, String subject) {
        public Key {
            Objects.requireNonNull(issuer, "issuer");
            Objects.requireNonNull(subject, "subject");
        }
    }

    @Override
    public String toString() {
        return "Identity[issuer=" + issuer + ", subject=" + subject + "]";
    }

    /** Builds an {@link Identity}. */
    public static final class Builder {
        private final String issuer;
        private final String subject;
        private String name;
        private Instant expiresAt;
        private final Set<String> scopes = new LinkedHashSet<>();
        private final Set<String> groups = new LinkedHashSet<>();
        private final Set<String> roles = new LinkedHashSet<>();
        private final Set<String> entitlements = new LinkedHashSet<>();
        private final Map<String, Object> claims = new LinkedHashMap<>();

        private Builder(String issuer, String subject) {
            this.issuer = requireText(issuer, "issuer");
            this.subject = requireText(subject, "subject");
        }

        public Builder name(String name)                         { this.name = name; return this; }
        public Builder expiresAt(Instant expiresAt)              { this.expiresAt = expiresAt; return this; }
        public Builder scopes(Collection<String> scopes)         { this.scopes.addAll(scopes); return this; }
        public Builder groups(Collection<String> groups)         { this.groups.addAll(groups); return this; }
        public Builder roles(Collection<String> roles)           { this.roles.addAll(roles); return this; }
        public Builder entitlements(Collection<String> values)   { this.entitlements.addAll(values); return this; }
        public Builder claims(Map<String, Object> claims)        { this.claims.putAll(claims); return this; }

        public Identity build() {
            Objects.requireNonNull(expiresAt, "expiresAt");
            return new Identity(this);
        }

        private static String requireText(String value, String what) {
            if (value == null || value.isBlank()) {
                throw new IllegalArgumentException(what + " must not be blank");
            }
            return value;
        }
    }
}
