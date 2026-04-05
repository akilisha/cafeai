# MILESTONE-11: Helidon Escape Hatch

**Status:** ✅ Complete  
**Completed:** April 2026  
**Tests added:** 7 (6 unit, 1 integration)  
**Regressions:** 0

---

## What Was Delivered

`app.helidon()` — a fluent configurator giving CafeAI developers direct access to the
underlying Helidon `WebServerConfig.Builder` and `HttpRouting.Builder` before the server
starts.

This replaces the planned `cafeai-mcp` module. The MCP server direction was abandoned because
Helidon's MCP extension requires Helidon Inject, which conflicts with CafeAI's pure SE model.
The escape hatch is the correct architectural answer: CafeAI provides the AI primitives,
Helidon provides the protocol infrastructure, and `app.helidon()` is the seam between them.

## Key Insight

CafeAI is an opinion on top of Helidon SE, not a cage around it. When a developer needs
a Helidon capability — TLS, gRPC, MCP, native health formats — that CafeAI doesn't abstract,
they reach through `app.helidon()` without abandoning the CafeAI programming model.

## API

```java
app.helidon()
   .server(builder -> /* WebServerConfig.Builder access */)
   .routing(routing -> /* HttpRouting.Builder access */);
```

Both consumers are applied in `listen()` after CafeAI's own routing is assembled.
