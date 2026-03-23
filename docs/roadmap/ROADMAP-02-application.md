# ROADMAP-02: `Application` — `app` Object

**Maps to:** Express `Application` — properties, events, and all methods  
**Module:** `cafeai-core`  
**ADR Reference:** ADR-001, ADR-002, ADR-005 §3  
**Depends On:** ROADMAP-01 Phase 1 (factory), ROADMAP-01 Phase 4 (Router)  
**Status:** 🔴 Not Started

---

## Objective

Implement the full `Application` object — the central hub of every CafeAI app.
This covers all properties (`app.locals`, `app.mountpath`), the mount event, and
all methods (`app.all`, `app.get`, `app.post`, `app.put`, `app.delete`, `app.patch`,
`app.use`, `app.route`, `app.param`, `app.set`, `app.get(setting)`, `app.enable`,
`app.disable`, `app.engine`, `app.render`, `app.path`, `app.listen`).

Note: `app.listen` is delivered in ROADMAP-01 Phase 1. It is listed here for
completeness but not re-implemented.

---

## Phases

---

### Phase 1 — `app.locals` / Application-Scoped State

**Goal:** Type-safe application-lifetime data store.

#### Input
- `CafeAIApp` implementation
- `java.util.concurrent.ConcurrentHashMap` as backing store
- Design decision from ADR-005: typed `app.local(key, value)` API

#### Tasks
- [ ] Implement `app.local(String key, Object value)` — store
- [ ] Implement `app.local(String key)` — untyped retrieve
- [ ] Implement `app.local(String key, Class<T> type)` — typed retrieve
- [ ] Thread-safety: `ConcurrentHashMap` backing store
- [ ] AI infrastructure keys pre-registered as constants:
  `Locals.AI_PROVIDER`, `Locals.MEMORY_STRATEGY`, `Locals.VECTOR_STORE`, etc.
- [ ] `app.locals()` — returns unmodifiable snapshot of all locals

#### Output
```java
app.local("appName", "CafeAI Demo")
app.local("version", "1.0.0")

String name = app.local("appName", String.class) // → "CafeAI Demo"
Map<String, Object> all = app.locals()            // snapshot
```

#### Acceptance Criteria
- [ ] Values persist for the lifetime of the application
- [ ] Typed retrieval throws `ClassCastException` on type mismatch (not silent null)
- [ ] `app.locals()` snapshot is unmodifiable
- [ ] Thread-safe: concurrent writes do not corrupt state
- [ ] Accessible from middleware via `req.app().local(key, type)`
- [ ] Unit tests: store, retrieve, typed retrieve, thread-safety

---

### Phase 2 — `app.mountpath` + Mount Event

**Goal:** Sub-app mount path tracking and mount event notification.

#### Input
- Sub-app (`CafeAI` instance used as a sub-application)
- Mount path set during `app.use(path, subApp)`

#### Tasks
- [ ] Implement `app.mountpath()` — returns mount path string for sub-apps
- [ ] Implement `app.mountpaths()` — returns `List<String>` for multi-path mounts
- [ ] Implement `app.onMount(Consumer<CafeAI> callback)` — mount event listener
- [ ] Fire `onMount` callbacks when sub-app is mounted via `app.use(path, subApp)`
- [ ] Parent app reference stored on sub-app after mounting

#### Output
```java
var admin = CafeAI.create();
admin.onMount(parent -> System.out.println("Mounted on: " + admin.mountpath()));
app.use("/admin", admin);
// → "Mounted on: /admin"
```

#### Acceptance Criteria
- [ ] `app.mountpath()` returns the correct path after mounting
- [ ] `app.mountpaths()` returns all paths for multi-path mounts
- [ ] `onMount` callback fires exactly once per mount
- [ ] Unmounted app `mountpath()` returns empty string
- [ ] Unit + integration tests

---

### Phase 3 — HTTP Verb Methods

**Goal:** All HTTP method route registration methods.

#### Input
- `Router` interface methods (ROADMAP-01 Phase 4)
- Helidon SE routing API

#### Tasks
- [ ] Implement `app.get(path, handler)`
- [ ] Implement `app.post(path, handler)`
- [ ] Implement `app.put(path, handler)`
- [ ] Implement `app.patch(path, handler)`
- [ ] Implement `app.delete(path, handler)` — note: `delete` is a Java reserved word as a statement but legal as a method name on an object; verify IDE and compiler compatibility
- [ ] Implement `app.head(path, handler)`
- [ ] Implement `app.options(path, handler)`
- [ ] Implement `app.trace(path, handler)`
- [ ] Implement `app.all(path, handler)` — matches all HTTP methods
- [ ] Path parameter syntax: `:paramName` — consistent with Express
- [ ] Wildcard paths: `*`, `?`, `+` pattern matching
- [ ] Multiple handlers per route (middleware chain per route)

#### Output
```java
app.get("/users/:id", (req, res) -> res.json(userService.find(req.params("id"))));
app.post("/users", (req, res) -> res.status(201).json(userService.create(req.body(UserDto.class))));
app.delete("/users/:id", (req, res) -> { userService.delete(req.params("id")); res.sendStatus(204); });
app.all("/secret", (req, res, next) -> { log.info("Accessing secret"); next.run(); });
```

#### Acceptance Criteria
- [ ] All HTTP verbs route correctly to registered handlers
- [ ] Path parameters extracted and accessible via `req.params("name")`
- [ ] `app.all()` handler fires for every HTTP method on the path
- [ ] Multiple handlers per route execute in order, each calling `next.run()`
- [ ] 404 returned automatically for unmatched routes
- [ ] Route ordering respected (first match wins)
- [ ] Integration tests for each HTTP verb
- [ ] Integration test: wildcard and parameterised paths

---

### Phase 4 — `app.use()` Middleware Mounting

**Goal:** Global and path-scoped middleware registration.

#### Input
- `Middleware` interface
- Helidon SE filter chain

#### Tasks
- [ ] Implement `app.use(Middleware)` — global, all routes
- [ ] Implement `app.use(String path, Middleware)` — path-prefix scoped
- [ ] Implement `app.use(String path, Router)` — sub-router mounting
- [ ] Implement `app.use(String path, CafeAI)` — sub-app mounting (triggers mount event)
- [ ] Middleware execution order: registration order preserved
- [ ] `next.run()` passes control to the next middleware in chain
- [ ] Error-handling middleware: `(req, res, err, next)` four-argument form
- [ ] Short-circuit: if middleware does not call `next.run()`, chain stops

#### Output
```java
app.use(Middleware.requestLogger());           // global
app.use("/api", Middleware.auth());            // path-scoped
app.use("/api/v1", apiRouter);                 // sub-router
app.use("/admin", adminApp);                   // sub-app
```

#### Acceptance Criteria
- [ ] Global middleware executes for every request
- [ ] Path-scoped middleware executes only for matching path prefixes
- [ ] Middleware executes in registration order
- [ ] Chain stops when `next.run()` is not called
- [ ] Sub-router routes resolve correctly relative to mount path
- [ ] Mount event fires when sub-app is mounted
- [ ] Error-handling middleware catches exceptions from upstream middleware
- [ ] Integration tests: global, scoped, chained, sub-router

---

### Phase 5 — `app.param()`

**Goal:** Route parameter pre-processing middleware.

#### Input
- Route parameter extraction (Phase 3)

#### Tasks
- [ ] Implement `app.param(String name, ParamCallback callback)`
- [ ] `ParamCallback` signature: `(req, res, next, value)` — four arguments
- [ ] Callback fires before route handler when named param is present in URL
- [ ] Multiple `app.param()` calls for the same name execute in order
- [ ] Param callback fires once per request even if param appears multiple times

#### Output
```java
app.param("userId", (req, res, next, id) -> {
    User user = userService.find(id);
    if (user == null) { res.sendStatus(404); return; }
    req.setAttribute("user", user);
    next.run();
});

app.get("/users/:userId/profile", (req, res) -> {
    User user = req.attribute("user", User.class); // pre-loaded by param
    res.json(user.profile());
});
```

#### Acceptance Criteria
- [ ] Param callback fires before route handler
- [ ] Callback can short-circuit (not call `next.run()`) to reject request
- [ ] `req.attribute()` set in callback is available in handler
- [ ] Fires exactly once per request per param name
- [ ] Works on both `app` and `router` instances
- [ ] Unit + integration tests

---

### Phase 6 — `app.route()`

**Goal:** Fluent chainable route builder for a single path.

#### Input
- HTTP verb implementations (Phase 3)

#### Tasks
- [ ] Implement `app.route(String path)` returning a `Route` builder
- [ ] `Route` exposes: `.get()`, `.post()`, `.put()`, `.patch()`, `.delete()`,
  `.head()`, `.options()`, `.all()` — each returning `this` for chaining
- [ ] Internally registers routes on the same path without duplication

#### Output
```java
app.route("/books")
   .get((req, res) -> res.json(books.findAll()))
   .post((req, res) -> res.status(201).json(books.create(req.body(BookDto.class))))
   .put((req, res) -> res.json(books.update(req.body(BookDto.class))));
```

#### Acceptance Criteria
- [ ] All verbs on same path registered via single fluent chain
- [ ] Each verb handler independent
- [ ] Unregistered verbs on the path return 405 Method Not Allowed
- [ ] Unit + integration tests

---

### Phase 7 — `app.set()` / `app.get(Setting)` / `app.enable()` / `app.disable()`

**Goal:** Typed application settings management.

#### Input
- `Setting` enum defining all configurable settings

#### Tasks
- [ ] Define `Setting` enum — mirrors Express application settings:
  `ENV`, `TRUST_PROXY`, `X_POWERED_BY`, `ETAG`, `CASE_SENSITIVE_ROUTING`,
  `STRICT_ROUTING`, `JSON_ESCAPE_HTML`, `JSON_REPLACER`, `JSON_SPACES`,
  `JSONP_CALLBACK_NAME`, `QUERY_PARSER`, `SUBDOMAIN_OFFSET`, `VIEWS`, `VIEW_ENGINE`
- [ ] Implement `app.set(Setting, Object value)`
- [ ] Implement `app.setting(Setting)` — typed getter (avoids `app.get` name conflict)
- [ ] Implement `app.enable(Setting)` — sets boolean setting to `true`
- [ ] Implement `app.disable(Setting)` — sets boolean setting to `false`
- [ ] Implement `app.enabled(Setting)` — returns `true` if setting is truthy
- [ ] Implement `app.disabled(Setting)` — returns `true` if setting is falsy
- [ ] Default values matching Express defaults

#### Output
```java
app.set(Setting.ENV, "production")
app.enable(Setting.TRUST_PROXY)
app.disable(Setting.X_POWERED_BY)

app.enabled(Setting.TRUST_PROXY)  // → true
app.setting(Setting.ENV)          // → "production"
```

#### Acceptance Criteria
- [ ] All settings stored and retrieved correctly
- [ ] `enable`/`disable` work only on boolean settings (throw on non-boolean)
- [ ] Default values match Express defaults
- [ ] `Setting.ENV` defaults to `"development"` (matches Express)
- [ ] `Setting.X_POWERED_BY` defaults to `true` (matches Express)
- [ ] Unit tests for all settings

---

### Phase 8 — `app.engine()` + `app.render()`

**Goal:** Response formatter/template engine registration and rendering.

#### Input
- `ResponseFormatter` interface (CafeAI abstraction over template engines)

#### Tasks
- [ ] Define `ResponseFormatter` functional interface: `(Map<String,Object> locals) → String`
- [ ] Implement `app.engine(String ext, ResponseFormatter formatter)`
- [ ] Implement `app.render(String view, Map<String,Object> locals, RenderCallback)`
- [ ] Implement `app.render(String view, Map<String,Object> locals)` → `CompletableFuture<String>`
- [ ] Default view directory from `Setting.VIEWS`
- [ ] Default engine from `Setting.VIEW_ENGINE`
- [ ] Built-in: `ResponseFormatter.mustache()` — Mustache template support
- [ ] AI extension: `ResponseFormatter.markdown()` — renders Markdown responses
- [ ] AI extension: `ResponseFormatter.jsonSchema(schema)` — validates + formats structured AI output

#### Output
```java
app.set(Setting.VIEWS, "templates")
app.set(Setting.VIEW_ENGINE, "html")
app.engine("html", ResponseFormatter.mustache())
app.engine("md",   ResponseFormatter.markdown())

app.get("/email", (req, res) ->
    res.render("welcome", Map.of("name", "Ada")))
```

#### Acceptance Criteria
- [ ] Registered engine renders view correctly
- [ ] `app.render()` callback form works
- [ ] `app.render()` `CompletableFuture` form works
- [ ] Missing view returns 500 with meaningful error
- [ ] Missing engine returns 500 with meaningful error
- [ ] Mustache renderer works end-to-end
- [ ] Markdown renderer works end-to-end
- [ ] Unit + integration tests

---

### Phase 9 — `app.path()`

**Goal:** Returns the canonical path of the application.

#### Tasks
- [ ] Implement `app.path()` — returns absolute mounted path
- [ ] For root app: returns `""`
- [ ] For sub-app mounted at `/admin`: returns `"/admin"`
- [ ] For nested sub-app: returns full path, e.g. `"/admin/users"`

#### Acceptance Criteria
- [ ] Root app path is empty string
- [ ] Sub-app path reflects mount path
- [ ] Nested sub-app path is fully resolved
- [ ] Unit tests

---

## Definition of Done

- [ ] All nine phases complete
- [ ] All acceptance criteria passing
- [ ] Zero Checkstyle violations
- [ ] Javadoc on all public API members
- [ ] MILESTONE-02.md updated to reflect completion
