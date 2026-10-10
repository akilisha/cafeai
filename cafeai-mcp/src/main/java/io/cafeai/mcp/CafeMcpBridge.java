package io.cafeai.mcp;

import io.cafeai.core.CafeAI;
import io.cafeai.core.Locals;
import io.cafeai.core.identity.IdentityMode;
import io.cafeai.core.mcp.McpConfig;
import io.cafeai.core.spi.McpBridge;
import io.helidon.extensions.mcp.server.McpServerFeature;
import io.helidon.extensions.mcp.server.McpTool;
import io.helidon.http.HeaderNames;
import io.helidon.webserver.http.HttpRouting;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Gives {@code app.mcp()} its behaviour: collects the tools, and at {@code listen()}
 * mounts Helidon's MCP server at the configured path, out of reach of CafeAI's own
 * filters, with a filter that hands the caller's headers to route tools.
 */
public final class CafeMcpBridge implements McpBridge {

    @Override
    public McpConfig create(CafeAI app) {
        return new Config(app);
    }

    private static final class Config implements McpConfig {
        private final CafeAI app;
        private final CafeAI.HelidonConfig helidon;
        private final List<McpTool> tools = new ArrayList<>();
        private final Set<String> names = new LinkedHashSet<>();
        private final Set<String> forwarded = new LinkedHashSet<>(Set.of("Authorization"));
        private String path = "/mcp";
        private String serverName = "cafeai";
        private String serverVersion;

        Config(CafeAI app) {
            this.app = app;
            this.helidon = app.helidon();
            String version = CafeAI.class.getPackage().getImplementationVersion();
            this.serverVersion = version != null ? version : "dev";
            helidon.routing(this::install);
        }

        @Override
        public McpConfig tool(String name, String description, String route) {
            return tool(name, description, route, null);
        }

        @Override
        public McpConfig tool(String name, String description, String route, Class<?> input) {
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(description, "description");
            Objects.requireNonNull(route, "route");
            add(new RouteTool(name, description, route, input, app::port, app.audit()));
            return this;
        }

        @Override
        public McpConfig tools(Object... toolObjects) {
            for (Object o : toolObjects) {
                for (ObjectTool t : ObjectTool.from(Objects.requireNonNull(o, "tool object"), app.audit())) add(t);
            }
            return this;
        }

        @Override
        public McpConfig path(String path) {
            Objects.requireNonNull(path, "path");
            this.path = path.startsWith("/") ? path : "/" + path;
            return this;
        }

        @Override
        public McpConfig forwardHeaders(String... headerNames) {
            for (String h : headerNames) forwarded.add(Objects.requireNonNull(h, "header name"));
            return this;
        }

        @Override
        public McpConfig server(String name, String version) {
            this.serverName = Objects.requireNonNull(name, "name");
            this.serverVersion = Objects.requireNonNull(version, "version");
            return this;
        }

        private void add(McpTool tool) {
            if (!names.add(tool.name())) {
                throw new IllegalArgumentException("Two MCP tools are both named \"" + tool.name() + "\"");
            }
            tools.add(tool);
        }

        /** Runs at listen(): mounts the MCP server, keeping CafeAI's filters off its path. */
        private void install(HttpRouting.Builder routing) {
            // The endpoint is outside CafeAI's filters: in an app that serves verified callers,
            // nothing but Auth.mcp(...) would stop anyone listing and calling its tools.
            if (IdentityMode.enabled() && !path.equals(app.local(Locals.MCP_PROTECTED))) {
                throw new IllegalStateException("The MCP endpoint " + path + " is not protected, in an app "
                        + "that serves verified callers: CafeAI's filters (Auth.bearer) don't cover it. "
                        + "Add Auth.mcp(app, issuer, \"https://<host>" + path + "\") from cafeai-identity.");
            }
            // Off CafeAI's filters, but still a CafeAI request: @Tool methods see the caller.
            helidon.scoped(path);
            String prefix = path;
            routing.addFilter((chain, req, res) -> {
                String p = req.path().path();
                if (p.equals(prefix) || p.startsWith(prefix + "/")) {
                    Map<String, String> headers = new LinkedHashMap<>();
                    for (String name : forwarded) {
                        req.headers().first(HeaderNames.create(name)).ifPresent(v -> headers.put(name, v));
                    }
                    if (!headers.isEmpty()) req.context().register(new RouteTool.ForwardedHeaders(headers));
                }
                chain.proceed();
            });
            var server = McpServerFeature.builder()
                    .path(path)
                    .name(serverName)
                    .version(serverVersion);
            for (McpTool t : tools) server.addTool(t);
            routing.addFeature(server.build());
        }
    }
}
