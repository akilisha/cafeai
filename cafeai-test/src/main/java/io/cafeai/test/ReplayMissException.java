package io.cafeai.test;

/**
 * Thrown in {@link ReplayMode#REPLAY} when a model call has no recording, instead of
 * quietly calling the real model. The message names the request so it can be found
 * and recorded.
 */
public final class ReplayMissException extends RuntimeException {

    private final String key;

    ReplayMissException(String key, String lastUserMessage) {
        super("No recording for this model call (replay mode). Key " + key
                + (lastUserMessage == null ? "" : ", last user message: \"" + lastUserMessage + "\"")
                + ". Record it by running once with cafeai.replay.mode=auto (or record).");
        this.key = key;
    }

    /** The recording key the call would have used: its cassette is {@code <key>.json}. */
    public String key() {
        return key;
    }
}
