package io.cafeai.core.memory;

import io.cafeai.core.identity.Identity;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * Turns the conversation id a caller supplies into the key its history is stored under, so a
 * conversation belongs to the caller who started it.
 *
 * <p>With a verified identity on the current request, the key is scoped to that caller: another
 * caller sending the same id gets a conversation of their own, and never reads or continues this
 * one. The scope is a hash of the issuer and subject, so no personal data ends up in storage
 * keys. Without an identity, the id is used as given, as before identity existed; an id that
 * looks like a scoped key is refused, so an anonymous caller can't name a scoped conversation.
 *
 * <p>Used wherever CafeAI keys conversation memory: prompts, streams, vision, audio and agents.
 * Code that keys a {@link MemoryStrategy} by conversation itself should use it too.
 */
public final class ConversationKeys {

    /** The prefix of every caller-scoped key; reserved, so never accepted as an id. */
    public static final String SCOPED_PREFIX = "cafeai-identity:";

    private ConversationKeys() {}

    /**
     * The storage key for {@code conversationId} for the current request's caller.
     *
     * @return {@code null} when {@code conversationId} is {@code null} (no conversation)
     * @throws IllegalArgumentException if an id supplied without identity starts with
     *         {@link #SCOPED_PREFIX}
     */
    public static String forCurrentCaller(String conversationId) {
        return forCaller(Identity.current().orElse(null), conversationId);
    }

    /** The storage key for {@code conversationId} for {@code caller} ({@code null}: anonymous). */
    public static String forCaller(Identity caller, String conversationId) {
        if (conversationId == null) return null;
        if (caller == null) {
            if (conversationId.startsWith(SCOPED_PREFIX)) {
                throw new IllegalArgumentException("Conversation ids starting with '" + SCOPED_PREFIX
                        + "' are reserved for conversations of signed-in callers");
            }
            return conversationId;
        }
        return SCOPED_PREFIX + scope(caller.key()) + ":" + conversationId;
    }

    private static String scope(Identity.Key key) {
        try {
            MessageDigest sha = MessageDigest.getInstance("SHA-256");
            // A separator that can't appear in either part, so ("a:b","c") and ("a","b:c") differ.
            sha.update(key.issuer().getBytes(StandardCharsets.UTF_8));
            sha.update((byte) 0);
            sha.update(key.subject().getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(sha.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is always available", e);
        }
    }
}
