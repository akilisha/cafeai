package io.cafeai.identity;

import io.helidon.json.JsonObject;
import io.helidon.json.JsonParser;
import io.helidon.json.JsonValueType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Supplier;

/**
 * A terminal sign-in's tokens, kept between runs so the user signs in once: a file only the user
 * can read, renewed with the refresh token, revoked at the issuer on sign-out. Shared by
 * {@link DeviceLogin} and {@link LoopbackLogin}, which differ only in how they sign in; for the
 * same issuer, client and scope they share the file too.
 */
final class TokenCache {

    private static final Logger log = LoggerFactory.getLogger(TokenCache.class);
    private static final Duration RENEW_BEFORE = Duration.ofSeconds(30);

    private final TokenEndpoint tokens;
    private final Path file;   // null: nothing is kept
    private final Clock clock;

    TokenCache(TokenEndpoint tokens, Path file, Clock clock) {
        this.tokens = tokens;
        this.file = file;
        this.clock = clock;
    }

    /** {@code ~/.cafeai/tokens/<hash>.json}: one file per issuer, client and scope; the name gives nothing away. */
    static Path defaultFile(Issuer issuer, String clientId, String scope) {
        try {
            MessageDigest sha = MessageDigest.getInstance("SHA-256");
            for (String part : new String[]{issuer.id(), clientId, scope == null ? "" : scope}) {
                sha.update(part.getBytes(StandardCharsets.UTF_8));
                sha.update((byte) 0);
            }
            String name = HexFormat.of().formatHex(sha.digest()).substring(0, 32);
            return Path.of(System.getProperty("user.home"), ".cafeai", "tokens", name + ".json");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * A current access token: the cached one, renewed if it nears expiry, or {@code signIn}'s
     * when there is neither.
     */
    String accessToken(Supplier<TokenEndpoint.Token> signIn) {
        Instant now = clock.instant();
        TokenEndpoint.Token cached = load();
        if (cached != null && now.isBefore(cached.expiresAt().minus(RENEW_BEFORE))) {
            return cached.value();
        }
        if (cached != null && cached.refreshToken() != null) {
            try {
                Map<String, String> form = new LinkedHashMap<>();
                form.put("grant_type", "refresh_token");
                form.put("refresh_token", cached.refreshToken());
                return save(keepRefresh(tokens.request(form, now), cached)).value();
            } catch (TokenEndpoint.Refused e) {
                log.debug("Cached sign-in could not be renewed ({}); signing in again", e.error);
            }
        }
        return save(signIn.get()).value();
    }

    /** Revokes the cached refresh token at the issuer (RFC 7009), then deletes the file, even if the issuer can't be told. */
    SignedOut signOut() {
        if (file == null) return SignedOut.NOT_SIGNED_IN;
        TokenEndpoint.Token cached = load();
        SignedOut result = cached == null ? SignedOut.NOT_SIGNED_IN : SignedOut.FORGOTTEN;
        if (cached != null && cached.refreshToken() != null) {
            try {
                if (tokens.revoke(cached.refreshToken(), "refresh_token")) result = SignedOut.REVOKED;
            } catch (IdentityException e) {
                log.warn("Could not revoke the sign-in at the issuer; forgetting it here only: {}", e.getMessage());
            }
        }
        try {
            Files.deleteIfExists(file);
        } catch (IOException e) {
            throw new IdentityException("Could not delete the cached sign-in at " + file, e);
        }
        return result;
    }

    private TokenEndpoint.Token load() {
        if (file == null || !Files.isRegularFile(file)) return null;
        try {
            var value = JsonParser.create(Files.readString(file)).readJsonValue();
            if (value.type() != JsonValueType.OBJECT) return null;
            JsonObject json = value.asObject();
            return new TokenEndpoint.Token(json.stringValue("access_token").orElseThrow(),
                    Instant.ofEpochSecond(Long.parseLong(json.stringValue("expires_at").orElseThrow())),
                    json.stringValue("refresh_token").orElse(null), null);
        } catch (IOException | RuntimeException e) {
            log.warn("Ignoring an unreadable cached sign-in at {}: {}", file, e.toString());
            return null;
        }
    }

    /**
     * Writes the tokens where only this user can read them: owner-only permissions where the file
     * system has them (on Windows the user's profile directory is already private), and a write
     * to a temporary file then a move, so a crash never leaves a half-written file.
     */
    private TokenEndpoint.Token save(TokenEndpoint.Token token) {
        if (file == null) return token;
        var json = JsonObject.builder()
                .set("access_token", token.value())
                .set("expires_at", Long.toString(token.expiresAt().getEpochSecond()));
        if (token.refreshToken() != null) json.set("refresh_token", token.refreshToken());
        try {
            Path dir = file.toAbsolutePath().getParent();
            boolean posix = dir.getFileSystem().supportedFileAttributeViews().contains("posix");
            if (!Files.isDirectory(dir)) {
                if (posix) {
                    Files.createDirectories(dir, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
                } else {
                    Files.createDirectories(dir);
                }
            }
            Path temp = dir.resolve(file.getFileName() + "." + System.nanoTime() + ".tmp");
            if (posix) {
                Files.createFile(temp, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
            } else {
                Files.createFile(temp);
            }
            Files.writeString(temp, json.build().toString());
            try {
                Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            log.warn("Could not cache the sign-in at {}: {}", file, e.toString());
        }
        return token;
    }

    /** A renewal may omit a new refresh token; the old one is then kept. */
    private static TokenEndpoint.Token keepRefresh(TokenEndpoint.Token renewed, TokenEndpoint.Token old) {
        return renewed.refreshToken() != null ? renewed
                : new TokenEndpoint.Token(renewed.value(), renewed.expiresAt(), old.refreshToken(), renewed.idToken());
    }
}
