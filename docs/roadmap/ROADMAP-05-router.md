# ROADMAP-05: `Router` — Standalone Router Object

**Maps to:** Express `Router` — `router.all()`, `router.METHOD()`, `router.param()`, `router.route()`, `router.use()`  
**Module:** `cafeai-core`  
**ADR Reference:** ADR-005 §6  
**Depends On:** ROADMAP-02 Phases 3, 4, 5, 6  
**Status:** 🔴 Not Started

---

## Objective

Implement the standalone `Router` object — the modular route grouping primitive.
A `Router` is a mini-application: it has its own middleware stack and route
table, and can be mounted anywhere in a parent app or another router.
This is the composability backbone of large CafeAI applications.

Note: Most `Router` methods share implementation with `Application` methods
(ROADMAP-02). This roadmap focuses on the standalone Router lifecycle,
isolation properties, and nested mounting behaviour that go beyond what
`Application` covers.

---

## Phases

---

### Phase 1 — `CafeAI.Router()` Factory & Lifecycle

**Goal:** A working standalone `Router` instance with its own independent middleware stack.

#### Input
- `Router` interface (already defined)
- `SubRouter` implementation class

#### Tasks
- [ ] Implement `SubRouter` fully — isolated routing table and middleware chain
- [ ] `CafeAI.Router()` factory returns a new `SubRouter` instance
- [ ] `CafeAI.Router(RouterOptions)` with `caseSensitive`, `mergeParams`, `strict`
- [ ] Router is NOT a server — it has no `listen()` method
- [ ] Router can be mounted on `app` or on another `Router` via `.use()`
- [ ] Router maintains its own ordered middleware chain independent of parent
- [ ] Router's middleware chain executes AFTER the parent chain segments that precede the mount point

#### Output
```java
var router = CafeAI.Router();
router.use(Middleware.auth());
router.get("/profile", (req, res) -> res.json(req.attribute("user", User.class)));

app.use("/api", router);
// GET /api/profile → auth middleware → handler
```

#### Acceptance Criteria
- [ ] Router created independently of any app
- [ ] Router middleware executes in registration order
- [ ] Router mounted at a path prefix routes correctly
- [ ] Mounting same router at multiple paths works
- [ ] Router's middleware does NOT affect routes outside its mount path
- [ ] Unit test: router in isolation (no app)
- [ ] Integration test: router mounted on app

---

### Phase 2 — `router.METHOD()` — HTTP Verb Routes

**Goal:** Full HTTP method route registration on standalone routers.

#### Tasks
- [ ] Implement `router.get(path, handler)`
- [ ] Implement `router.post(path, handler)`
- [ ] Implement `router.put(path, handler)`
- [ ] Implement `router.patch(path, handler)`
- [ ] Implement `router.delete(path, handler)`
- [ ] Implement `router.head(path, handler)`
- [ ] Implement `router.options(path, handler)`
- [ ] Implement `router.all(path, handler)`
- [ ] Path params resolve relative to the router's own path, not the app root
- [ ] `req.baseUrl` set correctly inside router handlers

#### Output
```java
var usersRouter = CafeAI.Router();
usersRouter.get("/", (req, res) -> res.json(users.findAll()));
usersRouter.get("/:id", (req, res) -> res.json(users.find(req.params("id"))));
usersRouter.post("/", (req, res) -> res.status(201).json(users.create(req.body(UserDto.class))));

app.use("/users", usersRouter);
// GET  /users         → all users
// GET  /users/42      → user 42
// POST /users         → create user
```

#### Acceptance Criteria
- [ ] All HTTP verbs work on standalone routers
- [ ] `req.params("id")` resolves path params relative to router's path
- [ ] `req.baseUrl` reflects mount path inside router handlers
- [ ] `req.path` reflects path relative to router mount (not absolute)
- [ ] 404 for unmatched routes within router falls through to parent
- [ ] Integration tests: full CRUD via router

---

### Phase 3 — `router.use()` — Middleware & Nested Routers

**Goal:** Middleware mounting and nested router composition.

#### Tasks
- [ ] Implement `router.use(Middleware)` — scoped to this router
- [ ] Implement `router.use(String path, Middleware)` — path-scoped within router
- [ ] Implement `router.use(String path, Router)` — nested router mounting
- [ ] Nested router: paths resolve correctly through multiple levels of nesting
- [ ] Error-handling middleware: `(req, res, err, next)` four-argument form on router
- [ ] Middleware added to router AFTER route registration is still valid

#### Output
```java
var adminRouter = CafeAI.Router();
adminRouter.use(Middleware.requireRole("ADMIN"));

var usersSubRouter = CafeAI.Router();
usersSubRouter.get("/", (req, res) -> res.json(adminUsers.findAll()));

adminRouter.use("/users", usersSubRouter);
app.use("/admin", adminRouter);

// GET /admin/users → requireRole → handler
```

#### Acceptance Criteria
- [ ] Router middleware applies only within the router's scope
- [ ] Nested routers resolve full path correctly
- [ ] Three levels of nesting work: app → router → sub-router
- [ ] Error middleware on router catches errors from its routes
- [ ] Integration test: three-level nested routing

---

### Phase 4 — `router.param()`

**Goal:** Route parameter pre-processing scoped to the router.

#### Tasks
- [ ] Implement `router.param(String name, ParamCallback callback)`
- [ ] `router.param()` callbacks fire only for params defined in this router's routes
- [ ] Does NOT affect parent app param callbacks
- [ ] `mergeParams=true` makes parent params available AND triggers parent param callbacks

#### Output
```java
var router = CafeAI.Router(RouterOptions.mergeParams(true));

router.param("postId", (req, res, next, id) -> {
    req.setAttribute("post", posts.find(id));
    next.run();
});

router.get("/:postId/comments", (req, res) ->
    res.json(comments.forPost(req.attribute("post", Post.class))));

app.use("/blogs/:blogId/posts", router);
// req.params("blogId") available because mergeParams=true
```

#### Acceptance Criteria
- [ ] Router param callbacks fire for matching params in router routes
- [ ] Router param does NOT fire for params outside the router
- [ ] `mergeParams=true` makes parent params available in router param callbacks
- [ ] `mergeParams=false` (default): parent params NOT available
- [ ] Unit + integration tests for both `mergeParams` settings

---

### Phase 5 — `router.route()`

**Goal:** Fluent chainable route builder on standalone routers.

#### Tasks
- [ ] Implement `router.route(String path)` returning a `Route` builder
- [ ] `Route` builder scoped to the router (not the app)
- [ ] All HTTP verbs available on `Route`: `.get()`, `.post()`, `.put()`,
  `.patch()`, `.delete()`, `.all()`
- [ ] Route builder is fluent — each verb registration returns `this`

#### Output
```java
var router = CafeAI.Router();
router.route("/items/:id")
      .get((req, res) -> res.json(items.find(req.params("id"))))
      .put((req, res) -> res.json(items.update(req.params("id"), req.body(ItemDto.class))))
      .delete((req, res) -> { items.delete(req.params("id")); res.sendStatus(204); });

app.use("/api", router);
```

#### Acceptance Criteria
- [ ] All verbs on same path registered via single fluent chain
- [ ] Route is scoped to the router's mount path
- [ ] Unregistered verb returns 405 (Method Not Allowed)
- [ ] Integration test: full resource CRUD via `router.route()`

---

### Phase 6 — Router Composition Patterns

**Goal:** Validate the full composability story for real-world CafeAI applications.

#### Tasks
- [ ] End-to-end test: versioned API structure
- [ ] End-to-end test: role-scoped admin router
- [ ] End-to-end test: resource routers with shared param middleware
- [ ] Performance test: 10 levels of nested routers (edge case — ensure no stack overflow)
- [ ] Document recommended composition patterns in `docs/`

#### Output
```java
// Versioned API pattern
var v1 = CafeAI.Router();
var v2 = CafeAI.Router();

v1.use("/users", usersRouterV1);
v2.use("/users", usersRouterV2);

app.use("/api/v1", v1);
app.use("/api/v2", v2);
```

#### Acceptance Criteria
- [ ] Versioned API pattern resolves routes correctly
- [ ] Role-scoped routers apply middleware only within their scope
- [ ] 10-level deep nesting does not cause stack overflow
- [ ] Composition pattern documentation written

---

## Definition of Done

- [ ] All six phases complete
- [ ] All acceptance criteria passing
- [ ] Zero Checkstyle violations
- [ ] Javadoc on all public `Router` API members
- [ ] MILESTONE-05.md updated to reflect completion
