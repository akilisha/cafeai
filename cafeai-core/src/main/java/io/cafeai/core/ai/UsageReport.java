package io.cafeai.core.ai;

import io.cafeai.core.identity.Identity;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * Model usage so far, per route and per caller: how many model calls each route's requests
 * made, their tokens, and what they cost. From {@code app.usage()}.
 *
 * <p>Every model call counts -- prompts, streams, vision, history summaries, and each
 * round trip of an agent's tool loop -- credited to the route of the request that made
 * it. Calls made outside a request (at startup, from a background job) are under
 * {@link #NO_REQUEST}. Whisper transcription and text-to-speech are not counted: they
 * report no token usage.
 */
public record UsageReport(List<RouteUsage> routes, List<CallerUsage> callers) {

    /** The route name for model calls made outside any HTTP request. */
    public static final String NO_REQUEST = "(no request)";

    public UsageReport {
        routes = routes.stream().sorted(Comparator.comparing(RouteUsage::route)).toList();
        callers = callers.stream()
                .sorted(Comparator.comparing((CallerUsage c) -> c.caller().issuer())
                        .thenComparing(c -> c.caller().subject()))
                .toList();
    }

    /**
     * One caller's usage: the model calls made for requests carrying that verified identity
     * (see {@code cafeai-identity}). Anonymous calls, and calls made outside a request, are in
     * the route totals only.
     */
    public record CallerUsage(Identity.Key caller, long calls, long inputTokens, long outputTokens,
                              double cost, long unpricedCalls) {

        /** Every call was to a priced model, so {@code cost} is the whole cost. */
        public boolean costKnown() {
            return unpricedCalls == 0;
        }
    }

    /** One caller's usage, by the identity's key. */
    public Optional<CallerUsage> caller(Identity.Key caller) {
        return callers.stream().filter(c -> c.caller().equals(caller)).findFirst();
    }

    /**
     * One route's usage. {@code route} is the pattern that matched ({@code GET /orders/:id}).
     * {@code cost} is in dollars and covers the priced calls; {@code unpricedCalls} were to
     * models with no price, so their cost is unknown.
     */
    public record RouteUsage(String route, long calls, long inputTokens, long outputTokens,
                             double cost, long unpricedCalls) {

        /** Every call was to a priced model, so {@code cost} is the whole cost. */
        public boolean costKnown() {
            return unpricedCalls == 0;
        }

        /** The average cost of one model call, or empty if any call was unpriced. */
        public Optional<Double> costPerCall() {
            return calls == 0 || !costKnown() ? Optional.empty() : Optional.of(cost / calls);
        }
    }

    /** One route's usage, e.g. {@code route("GET /orders/:id")}. */
    public Optional<RouteUsage> route(String route) {
        return routes.stream().filter(r -> r.route().equals(route)).findFirst();
    }

    /** Every route together. */
    public RouteUsage total() {
        long calls = 0, in = 0, out = 0, unpriced = 0;
        double cost = 0;
        for (RouteUsage r : routes) {
            calls += r.calls();
            in += r.inputTokens();
            out += r.outputTokens();
            cost += r.cost();
            unpriced += r.unpricedCalls();
        }
        return new RouteUsage("(all)", calls, in, out, cost, unpriced);
    }
}
