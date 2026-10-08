package io.cafeai.identity;

/**
 * An issuer couldn't be used: its metadata or signing keys couldn't be read, or they don't
 * match what was configured. A configuration or availability problem, never a caller's bad
 * token (those are answered with {@code 401}, not thrown).
 */
public class IdentityException extends RuntimeException {

    public IdentityException(String message) {
        super(message);
    }

    public IdentityException(String message, Throwable cause) {
        super(message, cause);
    }
}
