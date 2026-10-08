package io.cafeai.identity;

import io.cafeai.core.identity.Identity;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Something a caller's token must carry for a route: a scope, role, group or entitlement
 * (the RFC 9068 claims), or any one of several.
 *
 * <p>Created by {@link Auth#scope}, {@link Auth#role}, {@link Auth#group},
 * {@link Auth#entitlement} and {@link Auth#anyOf}, and checked by {@link Auth#require}.
 * A requirement only reads what the issuer put in the token; the policy itself lives with
 * the issuer.
 */
public final class Requirement {

    private final String description;
    private final Predicate<Identity> test;
    private final Set<String> scopes;

    private Requirement(String description, Predicate<Identity> test, Set<String> scopes) {
        this.description = description;
        this.test = test;
        this.scopes = Collections.unmodifiableSet(new LinkedHashSet<>(scopes));   // declared order
    }

    static Requirement scope(String scope) {
        return new Requirement("scope " + scopeToken(scope), id -> id.hasScope(scope), Set.of(scope));
    }

    static Requirement role(String role) {
        return new Requirement("role " + text(role), id -> id.hasRole(role), Set.of());
    }

    static Requirement group(String group) {
        return new Requirement("group " + text(group), id -> id.inGroup(group), Set.of());
    }

    static Requirement entitlement(String entitlement) {
        return new Requirement("entitlement " + text(entitlement), id -> id.hasEntitlement(entitlement), Set.of());
    }

    static Requirement anyOf(List<Requirement> options) {
        if (options.isEmpty()) throw new IllegalArgumentException("anyOf needs at least one requirement");
        Set<String> scopes = new LinkedHashSet<>();
        options.forEach(o -> scopes.addAll(o.scopes));
        return new Requirement(
                "any of [" + String.join(", ", options.stream().map(o -> o.description).toList()) + "]",
                id -> options.stream().anyMatch(o -> o.test.test(id)),
                scopes);
    }

    /** Whether {@code identity} meets this requirement. */
    public boolean test(Identity identity) {
        return test.test(Objects.requireNonNull(identity, "identity"));
    }

    /**
     * The scopes that would satisfy it, named in the {@code 403} challenge as RFC 6750 asks.
     * Roles, groups and entitlements are never named to the caller.
     */
    Set<String> scopes() {
        return scopes;
    }

    @Override
    public String toString() {
        return description;
    }

    private static String text(String value) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException("must not be blank");
        return value;
    }

    /**
     * A scope must be one RFC 6749 scope token (printable ASCII, no space, {@code "} or
     * {@code \}), since it is matched against the token's space-separated {@code scope} and
     * quoted into the {@code 403} challenge.
     */
    private static String scopeToken(String scope) {
        text(scope);
        for (char c : scope.toCharArray()) {
            if (c < 0x21 || c > 0x7E || c == '"' || c == '\\') {
                throw new IllegalArgumentException("Not a valid scope (RFC 6749 3.3): " + scope);
            }
        }
        return scope;
    }
}
