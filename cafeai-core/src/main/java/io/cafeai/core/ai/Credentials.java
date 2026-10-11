package io.cafeai.core.ai;

import io.cafeai.core.internal.KeyVendor;
import io.cafeai.core.internal.SavedKeys;

import java.util.Objects;

/**
 * Where a provider's credential comes from, asked again on every request instead of fixed when
 * the provider's client is built.
 *
 * <p>The provider's client stays one shared, cached object; the credential is resolved for each
 * call, on the thread that makes it, so it can belong to the caller of the current request and
 * can expire and be renewed. That is what lets a model be reached as the signed-in user, or as
 * the app through OAuth, instead of under one long-lived key:
 *
 * <pre>{@code
 *   app.ai(OpenAI.of("<model-id>")
 *           .withBaseUrl("https://models.internal.example.com/v1")
 *           .withCredentials(OAuthCredentials.tokenExchange(issuer, client, "model-server")));
 * }</pre>
 *
 * <p>Implementations in {@code cafeai-identity} obtain OAuth tokens: as the app (client
 * credentials) or on behalf of the signed-in caller (token exchange). An implementation that
 * needs a caller and has none must throw, never fall back to some other credential.
 */
@FunctionalInterface
public interface Credentials {

    /**
     * The bearer token to send with this call. Called once per model call, on the calling
     * thread, so it may use {@code Identity.current()}. Should be fast: cache tokens until shortly
     * before they expire.
     *
     * @throws RuntimeException if no credential can be had for this call; the call then fails
     */
    String token();

    /**
     * Whether the credential is the caller's own (so the model endpoint decides, per caller,
     * whether the call is allowed). Such calls never use the semantic cache: an answer cached for
     * one caller would reach another without the endpoint being asked. Default: {@code false},
     * the same credential for everyone.
     */
    default boolean perCaller() {
        return false;
    }

    /**
     * Whether this is an API key, sent in the provider's key header (Anthropic's
     * {@code x-api-key}), rather than a token, sent as {@code Authorization: Bearer}. Default:
     * {@code false}, a token. For OpenAI-compatible endpoints both go as {@code Bearer}.
     */
    default boolean apiKey() {
        return false;
    }

    /**
     * The vendor's API key: its environment variable if set (for example {@code DEEPSEEK_API_KEY}),
     * else the key {@code cafeai login <vendor>} saved. Looked up on every call, so a key saved or
     * changed while the app runs is picked up. For the endpoints CafeAI has no factory for:
     *
     * <pre>{@code
     *   app.ai(Anthropic.of("<model-id>").withBaseUrl("https://api.deepseek.com/anthropic")
     *           .withCredentials(Credentials.saved("deepseek")));
     * }</pre>
     *
     * @param vendor one of {@code openai, grok, mistral, nova, kimi, deepseek}
     * @throws IllegalArgumentException for any other vendor
     */
    static Credentials saved(String vendor) {
        KeyVendor v = KeyVendor.byId(vendor).orElseThrow(() -> new IllegalArgumentException(
                "No saved keys for '" + vendor + "'. Known vendors: " + KeyVendor.ids()));
        return new Credentials() {
            @Override public String token() { return SavedKeys.require(v, v.id(), ""); }
            @Override public boolean apiKey() { return true; }
            @Override public String toString() { return "Credentials.saved(" + v.id() + ")"; }
        };
    }

    /**
     * A fixed key, given in code rather than read from the environment. For single-user use;
     * prefer OAuth credentials anywhere access must be accountable or revocable.
     */
    static Credentials staticKey(String key) {
        Objects.requireNonNull(key, "key");
        if (key.isBlank()) throw new IllegalArgumentException("key must not be blank");
        return new Credentials() {
            @Override public String token() { return key; }
            @Override public boolean apiKey() { return true; }
            @Override public String toString() { return "Credentials.staticKey(****)"; }
        };
    }
}
