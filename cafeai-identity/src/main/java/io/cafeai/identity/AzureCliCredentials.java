package io.cafeai.identity;

import io.cafeai.core.ai.Credentials;
import io.cafeai.core.internal.Cli;
import io.helidon.json.JsonObject;
import io.helidon.json.JsonParser;
import io.helidon.json.JsonValueType;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Microsoft Entra ID tokens from the developer's own Azure CLI sign-in ({@code az login}, or
 * {@code cafeai login azure}): no key, no client secret. For Claude in Microsoft Foundry and Azure
 * OpenAI on a developer's machine.
 *
 * <pre>{@code
 *   app.ai(Anthropic.of("claude-opus-5-5")
 *           .withBaseUrl("https://<resource>.services.ai.azure.com/anthropic")
 *           .withCredentials(AzureCliCredentials.scope(AzureCliCredentials.FOUNDRY)));
 * }</pre>
 *
 * <p>Runs {@code az account get-access-token --scope <scope>} (what Azure's own SDK does in its
 * {@code AzureCliCredential}) and keeps the token until {@link #RENEW_BEFORE} before it expires:
 * {@code az} takes a second or more to start, so it is never run per call. Calls are the signed-in
 * developer's, under the company's Entra policies.
 */
public final class AzureCliCredentials implements Credentials {

    /** Claude in Microsoft Foundry. */
    public static final String FOUNDRY = "https://ai.azure.com/.default";
    /** Azure OpenAI. */
    public static final String AZURE_OPENAI = "https://cognitiveservices.azure.com/.default";

    static final Duration RENEW_BEFORE = Duration.ofMinutes(5);
    private static final Duration TIMEOUT = Duration.ofSeconds(60);
    static final String INSTALL = "The Azure CLI (az) isn't installed. Install it from "
            + "https://learn.microsoft.com/cli/azure/install-azure-cli, then sign in: `cafeai login azure`.";

    private record Token(String value, Instant expires) { }

    private final String scope;
    private String tenant;
    private Supplier<Optional<Path>> locate = () -> Cli.find("az", System::getenv);
    private Function<List<String>, Cli.Result> runner = command -> Cli.run(command, TIMEOUT);
    private Clock clock = Clock.systemUTC();
    private Token cached;

    private AzureCliCredentials(String scope) {
        this.scope = Objects.requireNonNull(scope, "scope");
    }

    /** Tokens for this scope, e.g. {@link #FOUNDRY} or {@link #AZURE_OPENAI}. */
    public static AzureCliCredentials scope(String scope) {
        return new AzureCliCredentials(scope);
    }

    /** A tenant other than the signed-in account's default one. */
    public AzureCliCredentials tenant(String tenantId) {
        this.tenant = Objects.requireNonNull(tenantId, "tenantId");
        return this;
    }

    AzureCliCredentials cli(Supplier<Optional<Path>> locate, Function<List<String>, Cli.Result> runner, Clock clock) {
        this.locate = locate;
        this.runner = runner;
        this.clock = clock;
        return this;
    }

    @Override
    public synchronized String token() {
        Instant now = clock.instant();
        if (cached != null && now.isBefore(cached.expires().minus(RENEW_BEFORE))) return cached.value();
        Path az = locate.get().orElseThrow(() -> new IdentityException(INSTALL));
        var command = new ArrayList<>(List.of(az.toString(), "account", "get-access-token", "--scope", scope, "--output", "json"));
        if (tenant != null) command.addAll(List.of("--tenant", tenant));
        Cli.Result result = runner.apply(command);
        if (!result.ok()) {
            String said = firstLine(result.err());
            if (result.err().contains("az login")) {
                throw new IdentityException("Not signed in to Azure, or the sign-in has expired. Sign in: "
                        + "`cafeai login azure` (az login). Azure CLI said: " + said);
            }
            throw new IdentityException("The Azure CLI couldn't get a token for " + scope + ": " + said);
        }
        JsonObject json = parse(result.out());
        String token = json.stringValue("accessToken").orElseThrow(() ->
                new IdentityException("The Azure CLI answered without an accessToken"));
        // expires_on is UTC epoch seconds (az 2.54+); expiresOn is local time, so it's not used.
        Instant expires = json.value("expires_on").map(v -> v.type() == JsonValueType.NUMBER
                        ? Instant.ofEpochSecond(v.asNumber().longValue())
                        : Instant.ofEpochSecond(Long.parseLong(v.asString().value())))
                .orElse(now.plus(Duration.ofMinutes(30)));
        cached = new Token(token, expires);
        return token;
    }

    private static JsonObject parse(String out) {
        try {
            var value = JsonParser.create(out).readJsonValue();
            if (value.type() == JsonValueType.OBJECT) return value.asObject();
        } catch (RuntimeException ignored) {
            // reported below
        }
        throw new IdentityException("The Azure CLI's answer wasn't JSON: " + firstLine(out));
    }

    private static String firstLine(String s) {
        String t = s == null ? "" : s.strip();
        int nl = t.indexOf('\n');
        return nl < 0 ? t : t.substring(0, nl).strip();
    }

    @Override
    public String toString() {
        return "AzureCliCredentials(" + scope + ")";
    }
}
