# Extending CafeAI

CafeAI has no annotation scanner and no DI container. Every extension point is a
plain Java interface discovered through `java.util.ServiceLoader`. There are three
levels, from "pass an object" to "ship a jar that configures the app".

---

## Level 1 — implement an interface, pass the instance

Most of CafeAI's surface takes an interface. Your own implementation is a
first-class citizen with no registration ceremony.

```java
// A custom guardrail — GuardRail is just an interface
class BusinessHoursGuard implements GuardRail {
    public String   name()     { return "business-hours"; }
    public Position position() { return Position.PRE_LLM; }
    public Action   action()   { return Action.BLOCK; }
    public void handle(Request req, Response res, Next next) {
        if (LocalTime.now().isBefore(LocalTime.of(9, 0))) {
            res.status(503).json(Map.of("error", "Support opens at 9am"));
            return;                       // don't call next.run() → request stops here
        }
        next.run();
    }
}

app.guard(new BusinessHoursGuard());
```

The same applies to `Middleware`, `AiProvider`, `WsHandler`, `ResponseFormatter`,
and the `Retriever` / `VectorStore` / `EmbeddingModel` interfaces in `cafeai-rag`.
If you only need it in one app, stop here.

---

## Level 2 — a provider SPI

To make a **factory method light up** when your jar is on the classpath —
`MemoryStrategy.redis(...)` working only because `cafeai-memory` is present — you
implement a provider SPI and declare it in `META-INF/services/`.

### The SPI catalogue

All live in `io.cafeai.core.spi`. `cafeai-core` loads each via `ServiceLoader`
from the factory class named in the last column.

| SPI | Unlocks | Implemented today by | Loaded from |
|---|---|---|---|
| `MemoryStrategyProvider` | `MemoryStrategy.mapped()/redis()/chronicle()/hybrid()` | `cafeai-memory` | `MemoryStrategy` |
| `RagPipeline` | `app.ingest()` + RAG retrieval on `app.prompt()` / agents | `cafeai-rag` | `CafeAIApp`, `cafeai-agents` |
| `GuardRailProvider` | real `GuardRail.pii()/jailbreak()/regulatory()/...` (stubs without it) | `cafeai-guardrails` | `GuardRail` |
| `ObserveBridge` | `app.observe(...)` tracing / spans | `cafeai-observability` | `CafeAIApp` |
| `ConnectBridge` | `app.connect(...)` connectors + fallback | `cafeai-connect` | `CafeAIApp` |
| `AgentBridge` | `app.agent(...)` | `cafeai-agents` | `CafeAIApp` |
| `ViewEngineProvider` | `app.engine(...)` / `res.render(...)` | `cafeai-views-mustache` | `CafeAIApp` |

Each SPI's Javadoc is the contract. Parameters are typed `Object` where the SPI
would otherwise force a compile-time dependency the other way — the implementer
casts to the concrete types it owns.

### Worked example — `cafeai-pgvector-lite`

A hypothetical module adding a second pgvector-style store. (In practice you'd
implement `io.cafeai.rag.VectorStore` and hand the instance to `app.vectordb(...)`
— Level 1 — but this shows the SPI shape.)

```groovy
// cafeai-pgvector-lite/build.gradle
plugins { id 'java-library' }
dependencies {
    api 'com.akilisha.oss:cafeai-core:0.2.0'
    implementation 'com.akilisha.oss:cafeai-rag:0.2.0'
    implementation 'org.postgresql:postgresql:42.7.7'
}
```

```java
package com.example.pgvlite;

public final class PgVectorLiteProvider implements io.cafeai.core.spi.MemoryStrategyProvider {
    // implement the SPI's methods — see MemoryStrategyProvider Javadoc for the contract
}
```

```
# src/main/resources/META-INF/services/io.cafeai.core.spi.MemoryStrategyProvider
com.example.pgvlite.PgVectorLiteProvider
```

Add the jar to any CafeAI app and the capability is present. Remove it and the
factory method throws a clear "add this dependency" exception — the same
behaviour as calling `app.prompt()` with no provider registered.

### Testing an SPI implementation

```java
@Test
void provider_isDiscovered() {
    var found = java.util.ServiceLoader.load(MemoryStrategyProvider.class)
        .stream().map(p -> p.get().getClass()).toList();
    assertThat(found).contains(PgVectorLiteProvider.class);
}
```

Provider methods are ordinary code — test them directly with fakes. See
`cafeai-agents`' `AgentRegistryTest` for the pattern (fake `ChatModel`, fake
support object, real assembly).

---

## Level 3 — `CafeAIConfigurer`: a jar that configures the app

`CafeAIConfigurer` is the seam between your composition code and CafeAI bootstrap.
It is how a shared jar (an internal "company platform" module, a set of standard
routes and filters) wires itself into every app that depends on it.

```java
public interface CafeAIConfigurer {
    void configure(CafeAI app);
    default int order() { return 0; }   // lower runs first
}
```

Three ways it runs, all composable in the same app:

```java
// 1. Explicit — direct main() wiring, no ServiceLoader
var app = CafeAI.create();
app.configure(new StandardRoutes(userService));
app.listen(8080);

// 2. ServiceLoader — configurer discovered at CafeAI.create()
//    META-INF/services/io.cafeai.core.spi.CafeAIConfigurer → com.acme.PlatformConfig
var app = CafeAI.create();   // PlatformConfig.configure(app) already ran
app.listen(8080);

// 3. Both — discovered configurers run first (by order()), then explicit ones
```

`configure()` after `listen()` throws `IllegalStateException`. The zero-config
path (no `META-INF/services` file, no `configure()` call) is always valid — this
is a plain framework, not a container.

### `CafeAIModule` — the announce hook

`CafeAIModule` (`name()` / `version()` / `register(CafeAIRegistry)`) is a
lightweight "I'm here" hook: on startup CafeAI logs each module it finds
(`CafeAI module loaded: cafeai-rag v0.2.0`). The capability wiring itself goes
through the provider SPIs above — `CafeAIModule` is informational, not
load-bearing. Implement it in a `cafeai-*` module so its presence is visible in
the startup log; it is not required for a Level-1 or Level-2 extension.

---

## Anti-patterns

- **Don't reach into `io.cafeai.core.internal`.** `CafeAIApp` and friends are
  package-private for a reason. Everything you need is on the `CafeAI` interface
  or in `io.cafeai.core.spi`.
- **Don't add a DI framework.** If you need object graphs assembled, do it in a
  `CafeAIConfigurer` with plain constructors. Helidon SE has no CDI container and
  CafeAI deliberately follows suit.
- **Don't wrap the escape hatch.** For app-specific Helidon wiring use
  `app.helidon()` (DEVELOPER_GUIDE §21). Build an SPI/module only for capabilities
  other apps would reuse.
