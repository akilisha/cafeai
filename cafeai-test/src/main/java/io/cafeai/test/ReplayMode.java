package io.cafeai.test;

/**
 * What {@link Replay} does with a model call.
 *
 * <table>
 *   <caption>Behaviour by mode</caption>
 *   <tr><th>Mode</th><th>A recording exists</th><th>No recording</th></tr>
 *   <tr><td>{@link #AUTO}</td><td>replay it</td><td>call the model and record it</td></tr>
 *   <tr><td>{@link #REPLAY}</td><td>replay it</td><td>fail with {@link ReplayMissException}</td></tr>
 *   <tr><td>{@link #RECORD}</td><td>call the model and re-record it</td><td>call the model and record it</td></tr>
 * </table>
 *
 * <p>{@code AUTO} suits development; {@code REPLAY} suits CI, where a test must never
 * reach a real model; {@code RECORD} refreshes recordings after a prompt or model change.
 */
public enum ReplayMode {
    AUTO, REPLAY, RECORD
}
