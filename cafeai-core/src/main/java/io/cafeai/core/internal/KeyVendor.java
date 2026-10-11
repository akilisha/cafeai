package io.cafeai.core.internal;

import java.util.Arrays;
import java.util.Locale;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * The vendors whose API key {@code cafeai login} can keep: each one's name on the command line,
 * the environment variable that takes precedence over the saved key, and where to create a key.
 * Public only so {@code io.cafeai.core.login} and {@code io.cafeai.core.ai} can reach it.
 */
public enum KeyVendor {

    OPENAI("openai", "OpenAI", "OPENAI_API_KEY", "https://platform.openai.com/api-keys"),
    GROK("grok", "xAI (Grok)", "XAI_API_KEY", "https://console.x.ai"),
    MISTRAL("mistral", "Mistral AI", "MISTRAL_API_KEY", "https://console.mistral.ai/api-keys"),
    NOVA("nova", "Amazon Nova", "NOVA_API_KEY", "https://nova.amazon.com/dev"),
    KIMI("kimi", "Kimi (Moonshot AI)", "MOONSHOT_API_KEY", "https://platform.moonshot.ai/console/api-keys"),
    DEEPSEEK("deepseek", "DeepSeek", "DEEPSEEK_API_KEY", "https://platform.deepseek.com/api_keys");

    private final String id;
    private final String displayName;
    private final String envVar;
    private final String keysPage;

    KeyVendor(String id, String displayName, String envVar, String keysPage) {
        this.id = id;
        this.displayName = displayName;
        this.envVar = envVar;
        this.keysPage = keysPage;
    }

    /** The name used on the command line and in the keys file, e.g. {@code openai}. */
    public String id() { return id; }

    public String displayName() { return displayName; }

    /** The environment variable that, when set, is used instead of the saved key. */
    public String envVar() { return envVar; }

    /** Where to create a key. */
    public String keysPage() { return keysPage; }

    /** The vendor with this command-line name, ignoring case. */
    public static Optional<KeyVendor> byId(String id) {
        if (id == null) return Optional.empty();
        String wanted = id.toLowerCase(Locale.ROOT);
        return Arrays.stream(values()).filter(v -> v.id.equals(wanted)).findFirst();
    }

    /** The vendor whose key this environment variable holds. */
    public static Optional<KeyVendor> byEnvVar(String envVar) {
        return Arrays.stream(values()).filter(v -> v.envVar.equals(envVar)).findFirst();
    }

    /** {@code openai, grok, ...}, for messages. */
    public static String ids() {
        return Arrays.stream(values()).map(KeyVendor::id).collect(Collectors.joining(", "));
    }
}
