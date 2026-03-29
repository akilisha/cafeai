package io.cafeai.core.ai;

import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A named, registered prompt template with {@code {{variable}}} interpolation.
 *
 * <p>Templates are registered via {@code app.template(name, body)} and retrieved
 * via {@code app.template(name)}. They provide a clean separation between
 * prompt engineering (done once at startup) and prompt execution (done per request).
 *
 * <pre>{@code
 *   // Register at startup
 *   app.template("classify",
 *       "Classify the following message into one of: {{categories}}.\nMessage: {{message}}");
 *
 *   app.template("summarize",
 *       "Summarize the following in {{maxWords}} words or less:\n\n{{content}}");
 *
 *   // Use in a handler
 *   app.post("/classify", (req, res, next) -> {
 *       String prompt = app.template("classify")
 *           .render(Map.of(
 *               "categories", "billing, shipping, returns",
 *               "message",    req.body("message")));
 *
 *       PromptResponse result = app.prompt(prompt).call();
 *       res.json(Map.of("category", result.text().trim()));
 *   });
 * }</pre>
 */
public final class Template {

    // Matches {{variableName}} -- variable names are word characters
    private static final Pattern VAR_PATTERN = Pattern.compile("\\{\\{(\\w+)\\}\\}");

    private final String name;
    private final String body;

    /** Package-private -- constructed by CafeAIApp */
    public Template(String name, String body) {
        this.name = name;
        this.body = body;
    }

    /** The registered name of this template. */
    public String name() { return name; }

    /** The raw template body with unsubstituted {@code {{variable}}} placeholders. */
    public String body() { return body; }

    /**
     * Renders the template by substituting all {@code {{variable}}} placeholders
     * with values from the given map.
     *
     * <p>Variables present in the template but absent from the map are left as-is --
     * this allows partial rendering. Variables present in the map but absent from
     * the template are silently ignored.
     *
     * @param vars variable names and their string values
     * @return the rendered template string
     */
    public String render(Map<String, Object> vars) {
        StringBuffer sb = new StringBuffer();
        Matcher m = VAR_PATTERN.matcher(body);
        while (m.find()) {
            String varName = m.group(1);
            Object value   = vars.get(varName);
            m.appendReplacement(sb,
                value != null
                    ? Matcher.quoteReplacement(value.toString())
                    : m.group(0));   // leave unreplaced if no value provided
        }
        m.appendTail(sb);
        return sb.toString();
    }

    /**
     * Renders the template, throwing {@link TemplateException} for any
     * {@code {{variable}}} placeholder that is not present in {@code vars}.
     *
     * <p>Use this variant when all variables are required -- it fails fast
     * rather than sending a half-rendered prompt to the LLM.
     *
     * @param vars variable names and their string values
     * @return the fully rendered template string
     * @throws TemplateException if any variable in the template has no value
     */
    public String renderStrict(Map<String, Object> vars) {
        StringBuffer sb    = new StringBuffer();
        Matcher      m     = VAR_PATTERN.matcher(body);
        while (m.find()) {
            String varName = m.group(1);
            Object value   = vars.get(varName);
            if (value == null) {
                throw new TemplateException(
                    "Template '" + name + "' requires variable '{{" + varName + "}}' " +
                    "but it was not provided. " +
                    "Provided variables: " + vars.keySet());
            }
            m.appendReplacement(sb, Matcher.quoteReplacement(value.toString()));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    /**
     * Thrown when a required template variable is missing.
     */
    public static final class TemplateException extends RuntimeException {
        public TemplateException(String message) {
            super(message);
        }
    }
}
