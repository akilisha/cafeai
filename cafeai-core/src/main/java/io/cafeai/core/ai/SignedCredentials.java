package io.cafeai.core.ai;

import java.net.URI;
import java.util.Map;

/**
 * Credentials that sign each request instead of carrying a token: the provider works out a
 * signature over the request itself (method, address, headers, body) and checks it, as AWS does
 * with Signature Version 4. Used like any other credentials,
 * {@code provider.withCredentials(signed)}; the request is signed just before it is sent, on the
 * calling thread, so the signing key can be the caller's.
 */
public interface SignedCredentials extends Credentials {

    /**
     * The headers to add so that this request is signed: the request is sent with them, its
     * other credential headers removed, and its body exactly as given here.
     *
     * @param method  the HTTP method
     * @param uri     the request's address
     * @param headers the request's headers that will be sent (including {@code host})
     * @param body    the request's body, empty if it has none
     * @throws RuntimeException if no signing key can be had for this call; the call then fails
     */
    Map<String, String> sign(String method, URI uri, Map<String, String> headers, byte[] body);

    /** Signed credentials carry no token. */
    @Override
    default String token() {
        throw new UnsupportedOperationException("Signed credentials sign each request; they have no token");
    }
}
