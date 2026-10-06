package io.cafeai.core.ai;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.OptionalDouble;

/**
 * What each model costs, in dollars per million tokens, so {@code app.usage()} and the
 * usage metrics can put a price on every route.
 *
 * <pre>{@code
 *   app.pricing(Pricing.of("gpt-4o-mini", 0.15, 0.60)
 *                      .and("claude-sonnet-4-5", 3.00, 15.00));
 * }</pre>
 *
 * <p>CafeAI ships no price list: prices change, like model ids, and the right numbers
 * are the ones on your bill. A model with no price still has its tokens counted; its
 * cost is reported as unknown, never as zero.
 *
 * <p>A model matches its exact id, or else the longest priced id it starts with -- so
 * {@code "gpt-4o-mini"} also prices a reported {@code "gpt-4o-mini-2024-07-18"}.
 */
public final class Pricing {

    private record Price(double inputPerMillion, double outputPerMillion) { }

    private final Map<String, Price> prices;

    private Pricing(Map<String, Price> prices) {
        this.prices = prices;
    }

    /** Prices {@code modelId} at {@code inputPerMillion} / {@code outputPerMillion} dollars per million tokens. */
    public static Pricing of(String modelId, double inputPerMillion, double outputPerMillion) {
        return new Pricing(Map.of()).and(modelId, inputPerMillion, outputPerMillion);
    }

    /** These prices, plus {@code modelId}'s. */
    public Pricing and(String modelId, double inputPerMillion, double outputPerMillion) {
        Objects.requireNonNull(modelId, "model id");
        if (inputPerMillion < 0 || outputPerMillion < 0) throw new IllegalArgumentException("prices cannot be negative");
        Map<String, Price> next = new LinkedHashMap<>(prices);
        next.put(modelId, new Price(inputPerMillion, outputPerMillion));
        return new Pricing(Map.copyOf(next));
    }

    /** The cost of a call to {@code modelId}, or empty if the model has no price. */
    public OptionalDouble cost(String modelId, long inputTokens, long outputTokens) {
        if (modelId == null) return OptionalDouble.empty();
        Price price = prices.get(modelId);
        if (price == null) {
            String best = null;
            for (String id : prices.keySet()) {
                if (modelId.startsWith(id) && (best == null || id.length() > best.length())) best = id;
            }
            if (best == null) return OptionalDouble.empty();
            price = prices.get(best);
        }
        return OptionalDouble.of((inputTokens * price.inputPerMillion() + outputTokens * price.outputPerMillion()) / 1_000_000d);
    }
}
