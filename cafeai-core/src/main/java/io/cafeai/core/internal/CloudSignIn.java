package io.cafeai.core.internal;

import io.cafeai.core.ai.AwsCliCredentials;
import io.cafeai.core.ai.AzureCliCredentials;
import io.cafeai.core.ai.Credentials;
import io.cafeai.core.ai.GoogleApplicationDefault;

import java.net.URI;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The developer's cloud CLI sign-in a provider uses by itself when it points at that cloud and
 * has no credentials of its own (LOGIN.md §9): Claude on Vertex AI, Claude in Microsoft Foundry,
 * Azure OpenAI, Claude in Amazon Bedrock. Only over https, and only to that cloud's own hosts.
 * One instance per sign-in, shared by every provider, so each CLI is asked once and its token kept.
 */
final class CloudSignIn {

    /** Which sign-in a provider uses: {@code google}, {@code azure-foundry}, {@code azure-openai}, {@code aws:<region>}. */
    record Kind(String key) { }

    private static final Pattern BEDROCK = Pattern.compile("bedrock-mantle\\.([a-z0-9-]+)\\.api\\.aws");
    private static final Map<String, Credentials> shared = new ConcurrentHashMap<>();

    /** Makes the credentials for a sign-in; replaced in tests. */
    static volatile Function<String, Credentials> factory = CloudSignIn::create;

    private CloudSignIn() {}

    /** Claude on Vertex AI: the Application Default Credentials. */
    static Credentials google() {
        return get(new Kind("google"));
    }

    /** For an Anthropic-compatible base URL: Foundry's or Bedrock's sign-in, or none. */
    static Optional<Credentials> forAnthropic(String baseUrl) {
        return anthropicKind(baseUrl).map(CloudSignIn::get);
    }

    /** For an OpenAI-compatible base URL: Azure OpenAI's sign-in, or none. */
    static Optional<Credentials> forOpenAi(String baseUrl) {
        return openAiKind(baseUrl).map(CloudSignIn::get);
    }

    static Optional<Kind> anthropicKind(String baseUrl) {
        String host = secureHost(baseUrl);
        if (host == null) return Optional.empty();
        if (host.endsWith(".services.ai.azure.com")) return Optional.of(new Kind("azure-foundry"));
        Matcher bedrock = BEDROCK.matcher(host);
        if (bedrock.matches()) return Optional.of(new Kind("aws:" + bedrock.group(1)));
        return Optional.empty();
    }

    static Optional<Kind> openAiKind(String baseUrl) {
        String host = secureHost(baseUrl);
        if (host == null) return Optional.empty();
        if (host.endsWith(".openai.azure.com") || host.endsWith(".cognitiveservices.azure.com")) {
            return Optional.of(new Kind("azure-openai"));
        }
        return Optional.empty();
    }

    private static Credentials get(Kind kind) {
        return shared.computeIfAbsent(kind.key(), factory);
    }

    /** Forgets the shared sign-ins (tests). */
    static void reset() {
        shared.clear();
    }

    private static Credentials create(String key) {
        if (key.equals("google")) return GoogleApplicationDefault.credentials();
        if (key.equals("azure-foundry")) return AzureCliCredentials.scope(AzureCliCredentials.FOUNDRY);
        if (key.equals("azure-openai")) return AzureCliCredentials.scope(AzureCliCredentials.AZURE_OPENAI);
        if (key.startsWith("aws:")) return AwsCliCredentials.active().region(key.substring("aws:".length()));
        throw new IllegalArgumentException(key);
    }

    private static String secureHost(String baseUrl) {
        try {
            URI uri = URI.create(baseUrl);
            if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null) return null;
            return uri.getHost().toLowerCase(Locale.ROOT);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
