package io.cafeai.core.login;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.cafeai.core.internal.Cli;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.ToIntFunction;
import java.util.function.UnaryOperator;

/**
 * {@code cafeai login azure | aws | google}: the cloud's own CLI sign-in ({@code az login},
 * {@code aws sso login}, {@code gcloud auth application-default login}), through the company's
 * single sign-on. CafeAI hands the terminal over and never sees a password; afterwards
 * {@code cafeai-identity}'s {@code AzureCliCredentials}, {@code AwsCredentials.fromCli()} and
 * {@code GoogleApplicationDefault} use the sign-in.
 */
final class CloudLogin {

    enum Cloud {
        AZURE("azure", "az", List.of("login"), List.of("logout"),
                "https://learn.microsoft.com/cli/azure/install-azure-cli"),
        AWS("aws", "aws", List.of("sso", "login"), List.of("sso", "logout"),
                "https://docs.aws.amazon.com/cli/latest/userguide/getting-started-install.html"),
        GOOGLE("google", "gcloud", List.of("auth", "application-default", "login"),
                List.of("auth", "application-default", "revoke"), "https://cloud.google.com/sdk/docs/install");

        final String id;
        final String program;
        final List<String> login;
        final List<String> logout;
        final String install;

        Cloud(String id, String program, List<String> login, List<String> logout, String install) {
            this.id = id;
            this.program = program;
            this.login = login;
            this.logout = logout;
            this.install = install;
        }

        static Optional<Cloud> byId(String id) {
            return Arrays.stream(values()).filter(c -> c.id.equalsIgnoreCase(id)).findFirst();
        }
    }

    /** How the cloud CLIs are found and run; replaced in tests. */
    record Tools(Function<String, Optional<Path>> find, Function<List<String>, Cli.Result> capture,
                 ToIntFunction<List<String>> handOver) {
        static Tools real(UnaryOperator<String> env) {
            return new Tools(name -> Cli.find(name, env), command -> Cli.run(command, Duration.ofSeconds(60)), Cli::handOver);
        }
    }

    private static final ObjectMapper JSON = new ObjectMapper();

    private final PrintStream out;
    private final PrintStream err;
    private final UnaryOperator<String> env;
    private final Tools tools;

    CloudLogin(PrintStream out, PrintStream err, UnaryOperator<String> env, Tools tools) {
        this.out = out;
        this.err = err;
        this.env = env;
        this.tools = tools;
    }

    int login(Cloud cloud, List<String> extra) {
        return handOver(cloud, cloud.login, extra, "sign-in");
    }

    int logout(Cloud cloud, List<String> extra) {
        return handOver(cloud, cloud.logout, extra, "sign-out");
    }

    private int handOver(Cloud cloud, List<String> args, List<String> extra, String what) {
        Optional<Path> program = tools.find().apply(cloud.program);
        if (program.isEmpty()) {
            err.println("`cafeai login " + cloud.id + "` uses the " + cloud.program + " CLI, which isn't on the PATH."
                    + " Install it from " + cloud.install);
            return 1;
        }
        var command = new ArrayList<String>();
        command.add(program.get().toString());
        command.addAll(args);
        command.addAll(extra);
        int status = tools.handOver().applyAsInt(command);
        if (status != 0) {
            err.println("cafeai: `" + cloud.program + " " + String.join(" ", args) + "` failed (exit " + status + ").");
            return 1;
        }
        if (what.equals("sign-in")) out.println(cloud.id + ": " + status(cloud, extra));
        return 0;
    }

    /** The {@code cafeai status} line for a cloud. */
    String status(Cloud cloud, List<String> extra) {
        if (cloud == Cloud.GOOGLE) return googleStatus();
        Optional<Path> program = tools.find().apply(cloud.program);
        if (program.isEmpty()) return cloud.program + " not installed";
        var command = new ArrayList<String>();
        command.add(program.get().toString());
        if (cloud == Cloud.AZURE) command.addAll(List.of("account", "show", "--output", "json"));
        else command.addAll(List.of("configure", "export-credentials", "--format", "process"));
        command.addAll(profileOnly(extra));
        Cli.Result result = tools.capture().apply(command);
        if (!result.ok()) return "not signed in (cafeai login " + cloud.id + ")";
        JsonNode json;
        try {
            json = JSON.readTree(result.out());
        } catch (IOException e) {
            return "signed in";
        }
        if (cloud == Cloud.AZURE) {
            return "signed in as " + json.path("user").path("name").asText("?") + ", subscription "
                    + json.path("name").asText("?") + " (tenant " + json.path("tenantId").asText("?") + ")";
        }
        String expires = json.path("Expiration").asText("");
        return expires.isEmpty() ? "credentials from the AWS CLI (long-lived keys)"
                : "signed in, credentials valid until " + expires;
    }

    /** Only {@code --profile <name>} carries over from a login's arguments to the status check. */
    private static List<String> profileOnly(List<String> extra) {
        int i = extra.indexOf("--profile");
        return i >= 0 && i + 1 < extra.size() ? List.of("--profile", extra.get(i + 1)) : List.of();
    }

    /** Reads the Application Default Credentials file instead of running gcloud, which is slow to start. */
    private String googleStatus() {
        Path file = adcFile();
        if (!Files.isRegularFile(file)) return "not signed in (cafeai login google)";
        try {
            JsonNode adc = JSON.readTree(file.toFile());
            String type = adc.path("type").asText("?");
            String account = adc.path("account").asText("");
            String quota = adc.path("quota_project_id").asText("");
            return "application default credentials (" + type + ")"
                    + (account.isEmpty() ? "" : " for " + account)
                    + (quota.isEmpty() ? "" : ", quota project " + quota);
        } catch (IOException e) {
            return "unreadable credentials file " + file;
        }
    }

    private Path adcFile() {
        String explicit = env.apply("GOOGLE_APPLICATION_CREDENTIALS");
        if (explicit != null && !explicit.isBlank()) return Path.of(explicit);
        String config = env.apply("CLOUDSDK_CONFIG");
        if (config != null && !config.isBlank()) return Path.of(config, "application_default_credentials.json");
        if (System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("windows")) {
            String appData = env.apply("APPDATA");
            if (appData != null && !appData.isBlank()) return Path.of(appData, "gcloud", "application_default_credentials.json");
        }
        return Path.of(System.getProperty("user.home"), ".config", "gcloud", "application_default_credentials.json");
    }
}
