# ROADMAP-08: Dependency Injection — CDI, Service Loaders, and `cafeai-cdi`

**Covers:** Three-tier composition model, `CafeAIConfigurer` seam, `CafeAIModule` SPI,  
Service Loader self-registration, optional `cafeai-cdi` module, `@CafeAIRoute`  
**Modules:** `cafeai-core` (SPI), `cafeai-cdi` (new optional module)  
**ADR Reference:** ADR-006  
**Depends On:** ROADMAP-01 Phase 1 (`CafeAI.create()` must exist)  
**Parallel With:** ROADMAP-02 through ROADMAP-07 (DI wires into Tier 2, not Tier 3)  
**Status:** 🟢 Complete (scoped) — see MILESTONE-08. The composition model shipped
(`CafeAIConfigurer`, `CafeAIModule`, ServiceLoader self-registration, the
spi/internal package split, `docs/EXTENDING.md`). Phases 4–5 (`cafeai-cdi`,
`@CafeAIRoute`) were **cut** — Helidon SE has no injection container and adding
CDI contradicts the framework's thesis. JPMS `module-info.java` was cut for
automatic-module friction. The roadmap phase bodies below are the original plan.

---

## Objective

Implement CafeAI's dependency injection architecture as defined in ADR-006.
The goal is a three-tier composition model where:

- **Tier 1** (CDI / manual wiring) assembles the object graph
- **Tier 2** (`CafeAIConfigurer`) bridges Tier 1 into CafeAI bootstrap
- **Tier 3** (Express API) handles requests — untouched by DI concerns

Zero-DI usage must remain a first-class path throughout. Every phase
must be completable without a CDI container.

---

## Phases

---

### Phase 1 — `CafeAIConfigurer` Interface + Service Loader Bootstrap

**Goal:** The integration seam between the user's dependency world and CafeAI's
configuration world. The most important interface in this roadmap.

**Module:** `cafeai-core`

#### Input
- `CafeAI` interface (ROADMAP-01 Phase 1)
- Java `ServiceLoader` API

#### Tasks

- [ ] Define `CafeAIConfigurer` interface in `io.cafeai.core.spi`:
  ```java
  public interface CafeAIConfigurer {
      void configure(CafeAI app);

      // Optional ordering — configurers with lower order run first
      default int order() { return 0; }
  }
  ```
- [ ] Integrate Service Loader discovery into `CafeAIApp.create()`:
  ```java
  ServiceLoader.load(CafeAIConfigurer.class)
      .stream()
      .map(ServiceLoader.Provider::get)
      .sorted(Comparator.comparingInt(CafeAIConfigurer::order))
      .forEach(c -> c.configure(this));
  ```
- [ ] Implement `CafeAI.configure(CafeAIConfigurer)` — explicit registration
  (for direct `main()` wiring without Service Loader)
- [ ] Implement `CafeAI.configure(CafeAIConfigurer...)` — multiple configurers
- [ ] Order guarantee: configurers execute in `order()` value ascending
- [ ] Idempotency: calling `configure()` after `listen()` throws `IllegalStateException`
- [ ] Document: Service Loader discovery happens at `CafeAI.create()` time,
  before any explicit `configure()` calls, before `listen()`

#### Output

```java
// Path 1: Zero DI — direct manual wiring
var app = CafeAI.create();
app.configure(new MyAppConfig(new OrderService(dataSource)));
app.listen(8080);

// Path 2: Service Loader discovery — CafeAIConfigurer in META-INF/services
// META-INF/services/io.cafeai.core.spi.CafeAIConfigurer
// → io.myapp.AppConfig
var app = CafeAI.create();  // discovers and runs AppConfig.configure() automatically
app.listen(8080);

// Path 3: CDI (via cafeai-cdi, Phase 4)
// @ApplicationScoped AppConfig implements CafeAIConfigurer
// → discovered and called automatically by CDI container
```

#### Acceptance Criteria
- [ ] `CafeAIConfigurer` in `META-INF/services` is discovered and called automatically
- [ ] Multiple configurers called in `order()` sequence
- [ ] `CafeAI.configure()` explicit call works without Service Loader
- [ ] Both paths (Service Loader + explicit) can be combined in same app
- [ ] `configure()` after `listen()` throws `IllegalStateException`
- [ ] Zero-DI path works with no `META-INF/services` file present
- [ ] Unit tests for each path
- [ ] Unit test: ordering of multiple configurers

---

### Phase 2 — `CafeAIModule` SPI + Module Self-Registration

**Goal:** Modules register their capabilities automatically when on the classpath.
No configuration required. Add the JAR, get the capability.

**Module:** `cafeai-core` (SPI definition), all `cafeai-*` modules (registrations)

#### Tasks

- [ ] Define `CafeAIModule` interface in `io.cafeai.core.spi`:
  ```java
  public interface CafeAIModule {
      String name();
      String version();
      void register(CafeAIRegistry registry);
  }
  ```
- [ ] Define `CafeAIRegistry` — the capability registration surface:
  ```java
  public interface CafeAIRegistry {
      void registerAiProvider(String name, AiProviderFactory factory);
      void registerMemoryStrategy(String name, MemoryStrategyFactory factory);
      void registerEmbeddingModel(String name, EmbeddingModelFactory factory);
      void registerVectorStore(String name, VectorStoreFactory factory);
      void registerGuardRail(String name, GuardRailFactory factory);
      void registerMiddleware(String name, MiddlewareFactory factory);
  }
  ```
- [ ] Integrate `CafeAIModule` Service Loader discovery into `CafeAIApp.create()`:
  ```java
  ServiceLoader.load(CafeAIModule.class)
      .forEach(module -> {
          log.info("CafeAI module loaded: {} v{}", module.name(), module.version());
          module.register(registry);
      });
  ```
- [ ] Implement `CafeAIMemoryModule` in `cafeai-memory` — registers memory strategies
- [ ] Implement `CafeAIRagModule` in `cafeai-rag` — registers embedding models + vector stores
- [ ] Implement `CafeAIGuardrailsModule` in `cafeai-guardrails` — registers guardrails
- [ ] Implement `CafeAIToolsModule` in `cafeai-tools` — registers MCP capabilities
- [ ] Each module writes its `META-INF/services/io.cafeai.core.spi.CafeAIModule` file
- [ ] Module loading is logged at INFO level on startup

#### Output

```
// Startup log when all modules on classpath:
[INFO] CafeAI module loaded: cafeai-memory v0.1.0
[INFO] CafeAI module loaded: cafeai-rag v0.1.0
[INFO] CafeAI module loaded: cafeai-guardrails v0.1.0
[INFO] CafeAI module loaded: cafeai-tools v0.1.0
[INFO] CafeAI module loaded: cafeai-cdi v0.1.0
```

#### Acceptance Criteria
- [ ] `cafeai-memory` on classpath → `MemoryStrategy.*` factories registered automatically
- [ ] `cafeai-rag` on classpath → `EmbeddingModel.*` and `VectorStore.*` registered automatically
- [ ] `cafeai-guardrails` on classpath → `GuardRail.*` factories registered automatically
- [ ] Removing a module JAR → its capabilities gracefully absent (no startup failure)
- [ ] Duplicate module registrations (same name) log a warning, last registration wins
- [ ] Module loading failures are logged but do not prevent application startup
- [ ] Unit tests: mock module registry, verify registrations
- [ ] Integration test: all modules on classpath, verify all capabilities available

---

### Phase 3 — SPI Package and `io.cafeai.core.internal`

**Goal:** Clean package visibility — SPI is public, implementation details are not.

**Module:** `cafeai-core`

#### Tasks
- [ ] Establish `io.cafeai.core.spi` as the public extension point package:
  - `CafeAIConfigurer` — the DI seam
  - `CafeAIModule` — the module SPI
  - `CafeAIRegistry` — the capability registration surface
  - All factory interfaces (`AiProviderFactory`, `MemoryStrategyFactory`, etc.)
- [ ] Establish `io.cafeai.core.internal` as package-private implementation territory:
  - `CafeAIApp` — package-private, never referenced by users
  - `SubRouter` — package-private
  - `BuiltInMiddleware` — package-private
  - `PipelineExecutor` — package-private
- [ ] Add `module-info.java` to `cafeai-core` (Java Platform Module System):
  ```java
  module io.cafeai.core {
      exports io.cafeai.core;
      exports io.cafeai.core.routing;
      exports io.cafeai.core.middleware;
      exports io.cafeai.core.ai;
      exports io.cafeai.core.config;
      exports io.cafeai.core.spi;
      // io.cafeai.core.internal is NOT exported

      uses io.cafeai.core.spi.CafeAIConfigurer;
      uses io.cafeai.core.spi.CafeAIModule;

      requires io.helidon.webserver;
      requires dev.langchain4j.core;
      requires com.fasterxml.jackson.databind;
      requires org.slf4j;
  }
  ```
- [ ] Verify module-info compiles cleanly with all declared dependencies
- [ ] Add `module-info.java` to each `cafeai-*` module declaring its provides/uses

#### Acceptance Criteria
- [ ] `io.cafeai.core.internal` inaccessible from outside `cafeai-core`
- [ ] `module-info.java` compiles cleanly for `cafeai-core`
- [ ] `module-info.java` compiles cleanly for all `cafeai-*` modules
- [ ] Service Loader still works with JPMS `provides`/`uses` declarations
- [ ] Unit test: attempt to access `CafeAIApp` directly from test code → compile error

---

### Phase 4 — `cafeai-cdi` Optional Module

**Goal:** CDI integration as a first-class, opt-in capability.
Adding `cafeai-cdi` to the classpath enables CDI-managed configurers
and injectable `CafeAI` bean. Nothing changes for users who don't add it.

**Module:** `cafeai-cdi` (new module)

#### Input
- Helidon CDI support (`io.helidon.microprofile.cdi`)
- `CafeAIConfigurer` interface (Phase 1)
- `CafeAIModule` SPI (Phase 2)

#### Tasks

- [ ] Create `cafeai-cdi` module with `build.gradle`
- [ ] Add to `settings.gradle`
- [ ] Implement `CafeAICdiExtension` as a CDI portable extension:
  - Discovers all CDI beans implementing `CafeAIConfigurer`
  - Calls `configure(app)` on each in `order()` sequence during startup
  - Fires after CDI injection is complete (all `@Inject` fields populated)
- [ ] Produce `CafeAI` as an `@ApplicationScoped` CDI bean:
  ```java
  @ApplicationScoped
  public class CafeAIProducer {
      @Produces
      @ApplicationScoped
      public CafeAI produceCafeAI() {
          return CafeAI.create();
          // CafeAICdiExtension handles configurer discovery and invocation
      }
  }
  ```
- [ ] Register `CafeAICdiModule` via Service Loader so CafeAI knows CDI is present
- [ ] `@Inject CafeAI app` works in any CDI bean
- [ ] Integration with Helidon's CDI lifecycle: CDI starts → configurers fire → `listen()` called

#### Output

```java
// With cafeai-cdi on classpath — no main() required
@ApplicationScoped
public class UserRoutes implements CafeAIConfigurer {

    @Inject UserService userService;       // CDI-managed — fully injected before configure() runs
    @Inject AuthMiddleware auth;           // CDI-managed middleware

    @Override
    public void configure(CafeAI app) {
        app.use("/users", auth);

        app.get("/users/:id", (req, res) ->
            res.json(userService.find(req.params("id"))));

        app.post("/users", (req, res) ->
            res.status(201).json(userService.create(req.body(UserDto.class))));
    }
}

// Elsewhere — CafeAI injectable
@ApplicationScoped
public class AdminService {
    @Inject CafeAI app;

    public void registerAdminRoutes() {
        app.get("/admin/health", (req, res) -> res.json(healthCheck()));
    }
}
```

#### Acceptance Criteria
- [ ] `@ApplicationScoped` `CafeAIConfigurer` implementations auto-discovered by CDI
- [ ] `@Inject` fields fully populated before `configure(app)` is called
- [ ] `@Inject CafeAI app` works in any CDI bean
- [ ] Ordering via `order()` respected across multiple CDI configurers
- [ ] CDI application without any `CafeAIConfigurer` starts without error
- [ ] Application without `cafeai-cdi` on classpath starts without error
- [ ] Integration test: CDI-managed configurer with injected service
- [ ] Integration test: `@Inject CafeAI` in a CDI bean

---

### Phase 5 — `@CafeAIRoute` Declarative Routing (Optional, Additive)

**Goal:** An optional annotation-based routing style for developers who prefer it.
Strictly additive — does not replace `app.get()`. Does not change the runtime model.

**Module:** `cafeai-cdi`

#### Tasks
- [ ] Define `@CafeAIRoute` annotation:
  ```java
  @Target(ElementType.TYPE)
  @Retention(RetentionPolicy.RUNTIME)
  public @interface CafeAIRoute {
      HttpMethod method();
      String path();
      int order() default 0;
  }
  ```
- [ ] Define `RouteHandler` interface:
  ```java
  @FunctionalInterface
  public interface RouteHandler {
      void handle(Request req, Response res);
  }
  ```
- [ ] `CafeAICdiExtension` scans for `@CafeAIRoute` CDI beans and registers
  them via `app.METHOD(path, handler)` — the annotation is purely declarative
  sugar over the programmatic API
- [ ] `@CafeAIRoute` beans and `app.get()` calls are interchangeable and composable

#### Output

```java
// Declarative style — optional
@CafeAIRoute(method = HttpMethod.GET, path = "/users/:id")
@ApplicationScoped
public class GetUserHandler implements RouteHandler {
    @Inject UserService userService;

    @Override
    public void handle(Request req, Response res) {
        res.json(userService.find(req.params("id")));
    }
}

// Produces exactly the same registration as:
app.get("/users/:id", (req, res) ->
    res.json(userService.find(req.params("id"))));
```

#### Acceptance Criteria
- [ ] `@CafeAIRoute` handler registered and reachable via HTTP
- [ ] `@Inject` fields populated in handler bean
- [ ] `@CafeAIRoute` and `app.get()` coexist in same application
- [ ] `@CafeAIRoute` beans not required to implement `RouteHandler` if they declare
  a single `void handle(Request, Response)` method (duck-typing via CDI producer)
- [ ] Unit test: annotation-registered route handles request correctly
- [ ] Integration test: mixed declarative + programmatic routes in same app

---

### Phase 6 — Documentation and Patterns

**Goal:** Capture the three-tier model as living documentation so future
contributors understand the architecture immediately.

**Module:** `docs/`

#### Tasks
- [ ] Write `docs/guide/DEPENDENCY-INJECTION.md`:
  - The three-tier model diagram
  - Zero-DI path (manual wiring) — full example
  - Service Loader path — full example + META-INF/services setup
  - CDI path (`cafeai-cdi`) — full example
  - When to use each path (decision guide)
  - Anti-patterns: what NOT to do (and why)
- [ ] Write `docs/guide/EXTENDING-CAFEAI.md`:
  - How to write a `CafeAIModule`
  - How to register capabilities via `CafeAIRegistry`
  - How to publish a `cafeai-*` extension module
  - Example: a hypothetical `cafeai-pinecone` module

#### Acceptance Criteria
- [ ] DI guide covers all three paths with working code examples
- [ ] Extension guide covers full module authoring workflow
- [ ] Both guides reviewed for accuracy against actual implementation
- [ ] Guides linked from main `README.md`

---

## Phase Dependencies

```
Phase 1  (CafeAIConfigurer + Service Loader bootstrap)
    └── Phase 2  (CafeAIModule SPI)      ← depends on CafeAIRegistry from Phase 1
    └── Phase 3  (SPI package + JPMS)    ← depends on Phases 1 and 2
    └── Phase 4  (cafeai-cdi module)     ← depends on Phase 1
            └── Phase 5  (@CafeAIRoute)  ← depends on Phase 4
    └── Phase 6  (documentation)         ← depends on all prior phases
```

---

## Interaction with Other Roadmaps

| Roadmap | Interaction |
|---|---|
| ROADMAP-01 | Phase 1 here adds Service Loader bootstrap to `CafeAIApp.create()` |
| ROADMAP-02 | `app.locals` becomes the vehicle for AI infrastructure after CDI wires it |
| ROADMAP-07 | Each Gen AI module (`cafeai-memory`, `cafeai-rag`, etc.) implements `CafeAIModule` |

---

## Definition of Done

- [ ] All six phases complete
- [ ] Zero-DI path tested and documented
- [ ] Service Loader path tested and documented
- [ ] CDI path tested and documented
- [ ] `cafeai-cdi` is a separately published optional module
- [ ] `module-info.java` present and correct for all `cafeai-*` modules
- [ ] No DI framework dependency in `cafeai-core`
- [ ] MILESTONE-08.md updated to reflect completion
