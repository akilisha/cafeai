package io.meridian.invoice.billing;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The JSON a tool returns to the model. A tool's arguments come from the model and can hold any character,
 * so the result is written by a JSON writer rather than joined from strings.
 */
final class ToolJson {

    private static final ObjectMapper MAPPER = new ObjectMapper()
        .enable(JsonGenerator.Feature.WRITE_BIGDECIMAL_AS_PLAIN);

    private ToolJson() {}

    /** An object from alternating keys and values, in the order given. */
    static String of(Object... keysAndValues) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i < keysAndValues.length; i += 2) {
            map.put((String) keysAndValues[i], keysAndValues[i + 1]);
        }
        try {
            return MAPPER.writeValueAsString(map);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    /** An amount of money as a number with two decimals. */
    static BigDecimal money(double amount) {
        return BigDecimal.valueOf(amount).setScale(2, RoundingMode.HALF_UP);
    }

    /** A percentage as a number with one decimal. */
    static BigDecimal percent(double pct) {
        return BigDecimal.valueOf(pct).setScale(1, RoundingMode.HALF_UP);
    }
}
