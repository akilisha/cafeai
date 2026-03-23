# ADR-007: Helidon SE — Natural Alignment with Express Routing Philosophy

**Status:** Accepted  
**Date:** March 2026

---

## Context

The choice of Helidon SE as CafeAI's HTTP runtime was documented in ADR-001
primarily on grounds of explicitness, Java 21+ support, and OTel integration.
This ADR captures a deeper and more specific rationale that emerged from
direct implementation experience: **Helidon SE's routing architecture is
philosophically aligned with Express in ways that no other Java framework
matches**, and this alignment is not accidental — it is architectural.

This distinction matters because it validates CafeAI's technical foundation
at a level beyond feature comparison. It explains *why* the Express-parity
translation is honest rather than forced, and *why* the CafeAI abstraction
layer can be thin rather than thick.

---

## The Two Specific Alignments

### Alignment 1: Nested Routing and Sub-Router Composition

#### The Express model
In Express, routing is compositional by design. A `Router` is a mini-application
that can be mounted at any path prefix on any other router or application:

```javascript
const usersRouter = express.Router()
usersRouter.get('/:id', getUser)
usersRouter.post('/', createUser)

app.use('/api/v1/users', usersRouter)
```

This is not a feature bolted onto Express — it is the *primary* API. Express
was designed around the idea that applications are composed from independently
defined routing units.

#### The Helidon SE model
Helidon SE's `Routing.builder()` API was designed with identical compositional
intent. The `.register(path, service)` method is the direct Helidon equivalent
of Express's `app.use(path, router)`:

```java
// Helidon SE native API
Routing.builder()
    .register("/api/v1/users", new UsersService())  // sub-service at path prefix
    .register("/api/v1/orders", new OrdersService())
    .build()
```

A `WebService` in Helidon SE is structurally identical to an Express `Router`:
it defines its own route handlers, has no knowledge of where it will be mounted,
and can be registered at any path prefix on any parent routing builder.

#### Why this matters for CafeAI
The CafeAI translation from Express to Helidon for nested routing is:

```
app.use('/api/v1', apiRouter)
    ↓
routing.register('/api/v1', apiRouter.toHelidonService())
```

This is a *one-line translation*. There is no structural impedance to overcome.
CafeAI does not need to implement a routing tree, a path prefix dispatcher, or
a handler chain manager. It wraps Helidon's existing composition primitives
with Express-familiar method names. The abstraction layer is thin because the
underlying models match.

With Jetty (and most other Java HTTP servers), this translation requires
reimplementing path-prefix dispatching from scratch — building a dispatch
table, managing handler hierarchies, and fighting the Servlet filter model
that was never designed for this kind of composition. The result inevitably
feels forced, because it is.

#### The failed alternative
Direct implementation experience confirmed this friction with Jetty:
implementing nested routing required building a custom `HandlerCollection`
dispatch mechanism that duplicated functionality Helidon SE provides natively.
The result was brittle, harder to test, and violated Jetty's own design
philosophy by using it as infrastructure it was not designed to be.

---

### Alignment 2: Path Parameters — Programmatic Access over Annotation Magic

#### The Express model
Express defines path parameters with `:name` syntax and accesses them
programmatically in the handler function:

```javascript
app.get('/users/:id', (req, res) => {
    const id = req.params.id   // programmatic access
    res.json(getUser(id))
})
```

No annotations. No compiler processing. No framework magic. The parameter
is a value in an object you already have. This is why Express handlers
are trivially unit-testable — you can construct a mock `req` with any
params you want and call the handler as a plain function.

#### The Helidon SE model
Helidon SE uses `{name}` syntax and provides programmatic access through
`ServerRequest`:

```java
// Helidon SE native API
routing.get("/users/{id}", (req, res) -> {
    String id = req.path().pathParameters().get("id");  // programmatic
    res.send(getUser(id));
})
```

The philosophy is identical: no annotations, no compile-time processing,
no framework intervention between the parameter value and your code.
The parameter is a value in an object you already have.

#### The syntax translation
The only visible difference is `:id` (Express) vs `{id}` (Helidon). CafeAI
resolves this completely at the abstraction layer:

```java
// CafeAI public API — Express syntax
app.get("/users/:id", (req, res) -> {
    String id = req.params("id");  // CafeAI ergonomic accessor
    res.json(userService.find(id));
})

// CafeAI internal translation — automatic, invisible to the developer
// ":id"  →  "{id}"   (single regex replacement on route registration)
// req.params("id")  →  helidonReq.path().pathParameters().get("id")
```

The public API is pure Express. The internal wiring is pure Helidon.
The translation is a one-liner in each direction. The developer never sees
`{id}` unless they look at the CafeAI source code.

#### Contrast: the annotation-based alternative
Spring MVC's approach to the same problem:

```java
@GetMapping("/users/{id}")
public ResponseEntity<User> getUser(@PathVariable String id) { ... }
```

This requires:
- Annotation processing at compile time
- Reflection at runtime
- A framework-managed invocation chain
- The handler is no longer a plain function — it is a method on a managed bean

The handler cannot be called directly. Testing requires the framework
container or a mock MVC harness. The simplicity of "a handler is a function
that receives req and res" is broken.

Helidon SE's programmatic model preserves this simplicity. CafeAI inherits
it directly.

#### Query parameters — the same story
Express:
```javascript
const page = req.query.page  // programmatic
```

Helidon SE:
```java
String page = req.query().get("page");  // programmatic
```

CafeAI:
```java
String page = req.query("page");  // ergonomic wrapper over Helidon
```

Same philosophy, same translation pattern, same thin abstraction.

---

## The Deeper Principle: Design Philosophy Alignment

Both Express and Helidon SE were built on the same foundational belief:

> *A routing framework should be a thin, compositional layer over HTTP —
> not a container that owns your code.*

This manifests in both frameworks as:
- Handlers are functions/lambdas, not managed objects
- Routing is composition, not configuration
- Parameters are values, not injected fields
- The framework serves your code — your code does not serve the framework

This philosophical alignment is why the CafeAI abstraction layer can be thin.
It is not papering over fundamental differences — it is providing ergonomic
vocabulary over structurally equivalent primitives.

Spring MVC, Quarkus, and Micronaut all invert this relationship in some form:
the framework owns your handlers (they must be beans), the framework owns your
parameters (they must be annotated), and the framework owns your routing
(it must be configured via annotations or XML). Building an Express-parity API
on top of any of these frameworks would require a thick abstraction layer that
fights the underlying model at every step.

Helidon SE does not require fighting. It requires *translating*.

---

## Consequences

- The CafeAI routing abstraction layer over Helidon SE is thin by design —
  not by laziness
- Path param translation (`:id` → `{id}`) is a one-liner at route registration
- `req.params("id")` wraps `req.path().pathParameters().get("id")` directly
- `req.query("key")` wraps `req.query().get("key")` directly
- Nested router composition maps directly to `.register(path, service)`
- Unit testing CafeAI handlers requires no framework harness — handlers are lambdas
- This alignment is a permanent architectural asset, not a temporary coincidence

---

## Final Statement

The combination of ADR-001 (Helidon SE for explicitness and Java 21+ support)
and this ADR (Helidon SE for routing philosophy alignment) gives CafeAI a
technically justified, experience-validated, and philosophically coherent
foundation. The choice was not made by reading feature comparison tables.
It was made by trying to build the same thing on a different foundation and
discovering where and why it failed.

> *Helidon SE does not need to be bent to meet CafeAI's goals.
> It was already going in the same direction.*

---

*ADR-007 — CafeAI v0.1.0-SNAPSHOT*
