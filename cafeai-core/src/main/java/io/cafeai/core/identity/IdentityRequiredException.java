package io.cafeai.core.identity;

/**
 * Work that must be done for a verified caller has none: a model call made on the caller's
 * behalf from an anonymous request, or caller-scoped work that lost its request on another
 * thread. CafeAI refuses rather than falling back to some other credential or to unscoped data.
 *
 * <p>Inside a request it is answered with {@code 401} and {@code WWW-Authenticate: Bearer}.
 */
public class IdentityRequiredException extends RuntimeException {

    public IdentityRequiredException(String message) {
        super(message);
    }
}
