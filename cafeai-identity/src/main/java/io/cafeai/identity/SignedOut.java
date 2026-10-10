package io.cafeai.identity;

/** What a terminal sign-out ({@link DeviceLogin#signOut()}, {@link LoopbackLogin#signOut()}) did. */
public enum SignedOut {
    /** Revoked at the issuer and deleted here. */
    REVOKED,
    /**
     * Deleted here only: the issuer publishes no {@code revocation_endpoint}, couldn't be
     * reached, or there was no refresh token. Its refresh token lives until it expires.
     */
    FORGOTTEN,
    /** There was no cached sign-in. */
    NOT_SIGNED_IN
}
