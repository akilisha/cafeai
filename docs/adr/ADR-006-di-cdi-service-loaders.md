# ADR-006: Dependency Injection — CDI, Service Loaders, and the Three-Tier Model

**Status:** Accepted  
**Date:** March 2026

---

## Context

Dependency Injection is not optional in serious Java development. It is not a
framework feature — it is a *language-level expectation*. Every Java developer
arrives at a framework with the assumption that *something* is managing their
object graph, their lifecycles, and their dependency wiring. Ignoring this is
not opinionated minimalism. It is an incomplete framework.

At the same time, CafeAI's foundational philosophy is Express-parity: the
routing and request-handling API must remain clean, ergonomic, and free of
Java-specific ceremony. Express has no DI story because JavaScript doesn't need
one — modules and closures are the dependency mechanism. Forcing DI concepts
into the Express-style API would produce exactly the wrong outcome: Spring MVC
with Express syntax. That is not CafeAI. That is a fraud.

The tension is therefore:

> *Java developers expect DI. Express-parity requires DI to stay out of the
> routing API. Both requirements must be met simultaneously without compromise.*

This ADR records how CafeAI resolves that tension.

---

## The Wrong Answers (Explicitly Rejected)

### ❌ Option A — DI is entirely internal and invisible

CafeAI uses CDI internally but exposes nothing to users.

**Why rejected:** This leaves users without a DI story for their own components —
their `OrderLookupTool`, their `CustomerService`, their custom guardrail
implementations. Without guidance, they reach for Spring just to get DI,
which defeats the entire purpose of CafeAI. An incomplete framework is not
a framework.

### ❌ Option C — DI baked into the Express API

Every handler, middleware, and tool is a CDI bean. Routes registered via
annotations. The `@CafeAIRoute` annotation becomes the primary API.

**Why rejected:** This *is* Spring MVC with Express syntax. It requires a CDI
container to run a hello world example. It breaks the simplicity that makes
CafeAI's API compelling. It makes the framework feel like it is faking
something it is not. The moment a developer sees `@ApplicationScoped` on
a route handler, the Express mental model evaporates.

---

## The Decision: A Three-Tier Composition Model

CafeAI separates application concerns into three clean, orthogonal tiers:

```
┌─────────────────────────────────────────────────────────────┐
│  Tier 1 — Application Wiring                                │
│  CDI beans, Service Loaders, lifecycle management           │
│  "How your application components are assembled"            │
└──────────────────────┬──────────────────────────────────────┘
                       │ produces wired dependencies
                       ▼
┌─────────────────────────────────────────────────────────────┐
│  Tier 2 — CafeAI Bootstrap                                  │
│  app.ai(), app.memory(), app.tool(), app.guard()            │
│  "How CafeAI is configured with your components"            │
└──────────────────────┬──────────────────────────────────────┘
                       │ produces a configured application
                       ▼
┌─────────────────────────────────────────────────────────────┐
│  Tier 3 — Request Handling                                  │
│  app.get(), app.use(), req, res, Middleware                  │
│  "Where Express lives — untouched"                          │
└─────────────────────────────────────────────────────────────┘
```

Each tier is:
- **Independently useful** — Tier 3 works with zero DI
- **Orthogonal** — changes in Tier 1 do not affect Tier 3
- **Composable** — all three tiers work together seamlessly

---

## The Integration Seam: `CafeAIConfigurer`

The bridge between Tier 1 and Tier 2 is a single interface:

```java
/**
 * The integration seam between dependency injection and CafeAI bootstrap.
 *
 * Implement this interface to configure a CafeAI application with
 * injected dependencies. CafeAI discovers implementations via:
 *   - Java Service Loader (META-INF/services)
 *   - CDI bean discovery (when cafeai-cdi is on the classpath)
 *   - Direct registration: CafeAI.create().configure(myConfigurer)
 */
public interface CafeAIConfigurer {
    void configure(CafeAI app);
}
```

This interface is deliberately minimal. It is a Java method. It does not know
whether CDI called it, whether Service Loader discovered it, or whether a
developer called it directly from `main()`. All three are equally valid.

### Without DI (zero ceremony, always supported):

```java
public static void main(String[] args) {
    var orderService = new OrderService(new JdbcOrderRepo(dataSource));

    var app = CafeAI.create();
    app.ai(OpenAI.gpt4o());
    app.tool(new OrderLookupTool(orderService));
    app.post("/chat", (req, res) -> res.stream(app.prompt(req.body("message"))));
    app.listen(8080);
}
```

### With CDI (injected dependencies flow through `configure()`):

```java
@ApplicationScoped
public class AppConfig implements CafeAIConfigurer {

    @Inject
    private OrderService orderService;       // CDI-managed

    @Inject
    private CustomerRepository customerRepo; // CDI-managed

    @Inject
    private AuthMiddleware auth;             // CDI-managed middleware

    @Override
    public void configure(CafeAI app) {
        app.ai(OpenAI.gpt4o());
        app.memory(MemoryStrategy.mapped());
        app.tool(new OrderLookupTool(orderService)); // injected dep flows in naturally
        app.guard(GuardRail.pii());

        app.use("/api", auth);

        app.post("/chat", (req, res) -> {
            // orderService available via closure — no framework magic needed
            var context = customerRepo.findContext(req.header("X-Session-Id"));
            res.stream(app.prompt(req.body("message")));
        });

        app.listen(8080);
    }
}
```

The Express API is completely unchanged. CDI manages the object graph.
`CafeAIConfigurer` is the clean seam where the two worlds meet.

---

## Service Loaders: The Module Extensibility Mechanism

Java's `ServiceLoader` is the right tool for CafeAI's *extensibility* story.
Not for user-facing DI — for *module self-registration*.

When `cafeai-rag` is on the classpath, it self-registers its capabilities.
When a third party publishes `cafeai-pinecone`, it drops a service descriptor
and CafeAI discovers it. No annotation scanning. No reflection. No XML.
Just the JDK's own extension mechanism, working exactly as designed.

### SPI contracts registered via Service Loader:

```
META-INF/services/io.cafeai.core.spi.CafeAIModule
META-INF/services/io.cafeai.core.spi.CafeAIConfigurer
META-INF/services/io.cafeai.core.ai.AiProvider
META-INF/services/io.cafeai.core.memory.MemoryStrategy
META-INF/services/io.cafeai.core.guardrails.GuardRail
META-INF/services/io.cafeai.core.rag.EmbeddingModel
META-INF/services/io.cafeai.core.rag.VectorStore
```

### `CafeAIModule` — the module self-registration contract:

```java
/**
 * Implemented by CafeAI modules to self-register their capabilities.
 * Discovered via ServiceLoader — no configuration required.
 *
 * Example: cafeai-rag implements CafeAIModule to register its
 * EmbeddingModel implementations and Retriever factories.
 */
public interface CafeAIModule {
    String name();
    String version();
    void register(CafeAIRegistry registry);
}
```

### Bootstrap discovery:

```java
// Inside CafeAIApp.create() — happens automatically
ServiceLoader.load(CafeAIModule.class)
    .forEach(module -> module.register(registry));

ServiceLoader.load(CafeAIConfigurer.class)
    .forEach(configurer -> configurer.configure(app));
```

The combination: CDI manages *your* object graph. Service Loaders manage
*CafeAI's* module graph. They are complementary, not competing.

---

## The Optional `cafeai-cdi` Module

CDI support is not in `cafeai-core`. It lives in an optional `cafeai-cdi`
module. This keeps the core dependency-free and ensures that a developer
who does not want CDI never pays the cost of it.

`cafeai-cdi` provides:

### 1. `CafeAI` as an injectable bean:

```java
@Inject
CafeAI app; // available anywhere in your CDI application
```

### 2. Automatic `CafeAIConfigurer` discovery:

CDI bean discovery automatically finds all `@ApplicationScoped` beans that
implement `CafeAIConfigurer` and calls `configure(app)` on them during
application startup.

### 3. Optional declarative routing (additive, not replacing):

```java
// For developers who prefer annotation-style — entirely optional
// Produces the same app.get() registration under the hood
@CafeAIRoute(method = HttpMethod.GET, path = "/users/:id")
@ApplicationScoped
public class GetUserHandler implements RouteHandler {
    @Inject UserService userService;

    @Override
    public void handle(Request req, Response res) {
        res.json(userService.find(req.params("id")));
    }
}
```

Critically: `@CafeAIRoute` is purely additive. It is a convenience. It does
not replace `app.get()`. It does not change the runtime model. It registers
a handler via `app.get()` internally. The two styles are interchangeable and
composable.

---

## Zero-DI as a First-Class Path

This must be stated explicitly and permanently:

**Zero-DI usage is a first-class, fully supported, never-deprecated path in CafeAI.**

A developer who wants to wire their application manually — passing dependencies
through constructors, using factory methods, managing lifecycles explicitly —
is not a second-class CafeAI citizen. They are using the framework exactly as
designed. The three-tier model works identically with or without DI in Tier 1.

This is the correct position because:
1. It keeps the entry barrier low — no container required to learn CafeAI
2. It keeps tests simple — no CDI harness needed for unit tests
3. It respects developer autonomy — CafeAI should not prescribe how you wire your app

---

## Consequences

- `cafeai-core` has zero DI dependencies (no CDI, no Spring, no Guice)
- `cafeai-cdi` is a new optional module providing CDI integration
- `CafeAIConfigurer` interface lives in `cafeai-core` (the seam must be in core)
- `CafeAIModule` SPI lives in `cafeai-core` (discovery is a core concern)
- Service Loader descriptors provided by each CafeAI module for self-registration
- ROADMAP-08 covers full implementation of this architecture

---

## Summary

| Concern | Mechanism | Where |
|---|---|---|
| Your object graph | CDI (optional) or manual | Your code + `cafeai-cdi` |
| Module self-registration | Service Loader | Each `cafeai-*` module |
| CafeAI configuration | `CafeAIConfigurer` seam | `cafeai-core` SPI |
| Route handling | Express API (Tier 3) | `cafeai-core` |
| DI framework freedom | Explicit design goal | Architecture |

> *DI is a parallel track. The Express API is untouched. Zero-DI is always supported.
> CDI is opt-in, never required. Service Loaders are how modules speak to the framework.
> These are three orthogonal concerns that compose without coupling.*

---

*ADR-006 — CafeAI v0.1.0-SNAPSHOT*
