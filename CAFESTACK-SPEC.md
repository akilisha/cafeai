# CafeStack — Specification

> A complete, opinionated development platform for modern web applications.
> Built on CafeAI. The LAMP of today's era.
> For developers who want to build, not configure.

---

## What CafeStack Is

CafeStack is not a framework. Frameworks give you components and let you
assemble them. CafeStack is a platform — a curated set of tools that
identifies and names the mundane plumbing and ceremonies around modern
application development (security, resilience, deployment, observability,
data access, async jobs, email, secrets) and then selects low-friction,
resource efficient tools, while applying modern software design and
architecture principles, to handle each concern behind the scenes.

The developer is left with little to no chores outside of actual development.

LAMP succeeded because it made one opinionated choice for every irreducible
concern of its era and hid it completely. CafeStack applies the same
philosophy to the modern era. The constraint is the product.

**The contract:**
> Write handlers. The platform handles everything else.

### The Core Architectural Insight

CafeStack is organised around a **double funnel architecture** with a
**stateless zone** at its centre. This is the idea that makes everything
else possible.

Two high-capacity, purpose-built funnels bracket the application layer:

- **Ingress funnel (Traefik)** — absorbs all inbound traffic. SSL, auth,
  rate limiting, and load balancing happen here before a single line of
  application code runs.
- **Egress funnel (pgBouncer)** — governs all outbound data access.
  Connection pooling and read/write routing happen here, invisibly, before
  any query reaches PostgreSQL.

Between the two funnels sits the **stateless zone** — the application
instances running Helidon and CafeAI. This zone is elastic by design.
Because no application instance owns any state (sessions live in Valkey,
data lives in PostgreSQL), instances can appear and disappear freely.
The funnels do not care. The developer does not notice.

This is where autoscaling becomes trivial. Adding an instance to the
stateless zone requires no warm-up, no state migration, no coordination
ceremony. The zone grows under load and contracts when load drops. The
developer scales by changing one number.

If you know CafeAI, you know 90% of CafeStack. The `app` object is the
single point of contact between the developer and the entire platform.
There is no annotation model. There is no second API surface. Sidecars,
infrastructure, and orchestration are invisible — they appear around your
code when you deploy, not in it.

---

## The Architecture

```
[ Clients — Browser / Mobile / API consumers ]
         |
         | HTTPS — untrusted, external
         ↓
┌─────────────────────────────────────────────┐
│  TRAEFIK  —  INGRESS FUNNEL                 │
│  SSL termination (automatic, Let's Encrypt) │
│  JWT verification (GoTrue)                  │
│  Rate limiting                              │
│  Load balancing across app instances        │
│  HTTP/2, compression, static asset serving  │
└─────────────────────────────────────────────┘
         |
         | HTTP — trusted, internal, clean
         ↓
╔═════════════════════════════════════════════╗
║           STATELESS ZONE                    ║
║                                             ║
║  App instances (Helidon + CafeAI)           ║
║                                             ║
║  Sidecars (per instance, invisible):        ║
║    GoTrue  — authentication                 ║
║    OTel    — observability                  ║
║                                             ║
║  Shared services:                           ║
║    Valkey  — session store + cache          ║
╚═════════════════════════════════════════════╝
         |
         | connection pool, read/write routing
         ↓
┌─────────────────────────────────────────────┐
│  PGBOUNCER  —  EGRESS FUNNEL                │
│  Connection pooling                         │
│  Read/write routing (primary vs replica)    │
│  Back-pressure                              │
└─────────────────────────────────────────────┘
         |
         ↓
┌─────────────────────────────────────────────┐
│  POSTGRESQL  —  STATEFUL LAYER              │
│  Primary (writes)                           │
│  N read replicas (one config value)         │
│  Extensions: pgvector, PostGIS, etc.        │
└─────────────────────────────────────────────┘

Orchestration: k0s / k3s — manages everything above
```

### The Stateless Zone

Everything between the two funnel boundaries is stateless. This is the
defining constraint of the architecture and the source of all its scaling
properties.

A new application instance joining the stateless zone:
- connects to Valkey for session state
- connects to pgBouncer for data access
- registers with Traefik via k8s Service primitives automatically
- is immediately a full participant

No warm-up. No state migration. No configuration change. No code change.
Sessions live in Valkey, not application memory. The zone cannot leak
state. The funnel boundaries enforce this contract.

### Why k0s / k3s

Kubernetes (via k0s or k3s) is the correct orchestration layer for
CafeStack for three reasons:

**Autoscaling is a first-class primitive.** ReplicaSet and HPA (Horizontal
Pod Autoscaler) are built in. Scaling the stateless zone up and down based
on resource demand requires no additional tooling — it is the default
Kubernetes model.

**Service discovery is built in.** k8s Service primitives expose stable
DNS names and IPs to everything in the cluster. There is no need for a
separate service registry (Consul with Nomad, Eureka with Spring Boot).
This eliminates an entire category of infrastructure and the coordination
ceremony that comes with it.

**Ubiquity.** Every developer who has touched any cloud environment has
seen Kubernetes. CafeStack abstracts it completely — the developer never
touches k8s directly — but the infrastructure team already knows the model.

k0s and k3s are production-grade Kubernetes distributions with minimal
operational overhead. The full Kubernetes API is available. The weight of
managing it is not.

### Why Traefik

Traefik was built with Kubernetes in mind. It watches the Kubernetes API
directly and reconfigures itself automatically as pods appear and disappear.
SSL via Let's Encrypt is automatic. It sits naturally as a k8s ingress
controller — not bolted on, but native to the environment.

### Why GoTrue over Keycloak

GoTrue is lightweight, JWT-native, and proven at scale (Supabase runs it
in production for millions of users). Keycloak is powerful but brings an
admin UI, a realm concept, and a client configuration model — exactly the
kind of invisible complexity CafeStack is supposed to eliminate. GoTrue's
job is to issue and verify JWTs. It does that job without ceremony.

### Circuit Breaking at Network Boundaries

Every network boundary in the stateless zone — calls to Valkey, pgBouncer,
GoTrue, and the OTel collector — is protected by an automatic circuit
breaker. This is not configurable by the developer because it should not
need to be. A slow Valkey under memory pressure, a saturated pgBouncer
pool, or an unresponsive GoTrue instance are infrastructure problems. The
platform absorbs them. Without circuit breaking at these boundaries, a
degraded dependency causes threads to pile up, latency to spike, and the
entire stateless zone to become unresponsive — a cascade failure from a
single slow service. Circuit breaking is invisible when it works and
catastrophic when it is absent. CafeStack makes it present by default.

---

## The Component Decisions

| Concern | CafeStack answer | Why |
|---------|-----------------|-----|
| Ingress / SSL / load balancing | Traefik | k8s-native, automatic SSL, zero config |
| Authentication | GoTrue (sidecar) | Lightweight, JWT-native, Supabase-proven |
| Session + cache | Valkey | Redis-compatible, truly open source |
| Observability | OTel collector (sidecar) | Zero logging code in application |
| Orchestration + scaling | k0s / k3s | Native autoscaling, native discovery |
| Connection pooling | pgBouncer | Prevents database connection exhaustion |
| Circuit breaking | Automatic at every network boundary | Valkey, pgBouncer, GoTrue, OTel — no cascade failures |
| Database | PostgreSQL | One database, extensions cover everything |
| Transactional email | Resend | Clean API, excellent deliverability |
| Secrets | k8s Secrets + external-secrets-operator | Invisible to developer |

Every choice above is invisible to the developer. The developer does not
configure Traefik, GoTrue, Valkey, OTel, pgBouncer, or k8s directly.
CafeStack generates all configuration from `cafeai.yaml`, environment
variables, and the `cafe` CLI.

---

## The Module Architecture

CafeStack extends CafeAI through new modules that follow the same design
principles already established in the framework. There is no Spring, no
CDI, no annotation scanning. Every capability is wired via Java
ServiceLoader SPIs — the same mechanism CafeAI already uses for
`MemoryStrategyProvider`, `GuardRailProvider`, `ObserveBridge`,
`RagPipeline`, and `ConnectBridge`.

### The Pattern

`cafeai-core` owns the developer-facing API surface: interfaces, domain
objects, and SPI contracts. Implementation modules register themselves via
ServiceLoader. The `app` object never knows which jar delivered the
implementation — it discovers it at runtime.

This means:
- A developer who does not need email does not pull `cafeai-email`
- `app.email()` exists in core but throws a clear `IllegalStateException`
  if no `EmailProvider` is on the classpath — exactly the same behaviour
  as calling `app.prompt()` without a registered AI provider
- Each module has a single, well-defined responsibility
- `cafeai-core` remains focused on HTTP and Express-style primitives

### What `cafeai-core` Gains

New SPI contracts and domain objects are added to `cafeai-core` following
the existing package structure. No implementation details enter core.

```
io.cafeai.core.spi
  DataProvider          ← new (alongside MemoryStrategyProvider, GuardRailProvider, etc.)
  JobScheduler          ← new
  EmailProvider         ← new

io.cafeai.core.data
  DbHandle              ← new (returned by app.db())
  DataSource            ← new (factory, e.g. DataSource.pgBouncer())

io.cafeai.core.jobs
  Schedule              ← new (cron / interval declarations)

io.cafeai.core.email
  EmailMessage          ← new (fluent message builder)
```

### The New Implementation Modules

**`cafeai-data`** implements `DataProvider`. Owns the pgBouncer and
HikariCP wiring, fluent query API, Java record mapping, and consistency
routing. Add this module when the application needs database access.

**`cafeai-jobs`** implements `JobScheduler`. Owns the scheduling mechanism
and k8s CronJob manifest generation. Add this module when the application
needs background jobs.

**`cafeai-email`** implements `EmailProvider`. Owns the Resend HTTP client
integration. Add this module when the application needs transactional email.

**Secrets** are not a Java module. Secret delivery is a platform and CLI
concern — `cafeai.yaml` declares names, `cafe deploy` wires them as k8s
Secrets, and the application reads them as environment variables. No
application-side Java surface is required.

### The Java Modules Stand Alone

The CafeAI Java modules — including `cafeai-data`, `cafeai-jobs`, and
`cafeai-email` — can be used in any CafeAI application without the CLI.
A developer can use them today, write their own k8s manifests, run Docker
Compose manually, and call kubectl directly. The modules have no runtime
dependency on the CLI.

The CLI is what transforms the collection of modules into a *platform*.
It is a separate deliverable, built in a separate repository, and can be
developed in parallel with the Java module work.

---

## The Developer Experience

### The `app` Object

The developer's entire interface to CafeStack is the CafeAI `app` object.
The `app` object gains new primitives for CafeStack concerns via the new
modules. Everything else is already there from CafeAI.

```java
var app = CafeAI.create();

// AI — already in CafeAI
app.ai("tutor",         OpenAI.gpt4o());
app.ai("transcription", OpenAI.whisper());
app.ai("voice",         OpenAI.tts());

// Safety — already in CafeAI
app.guard(GuardRail.pii());
app.guard(GuardRail.jailbreak());

// Memory — already in CafeAI (Valkey-backed in CafeStack deployments)
app.memory(MemoryStrategy.redis(config));

// Data — cafeai-data module
app.db(DataSource.pgBouncer(config));

// Auth — declared once, enforced at Traefik ingress
app.filter(Auth.require().on("/api/**"));

// HTTP handlers — already in CafeAI
app.get("/claims/:id", (req, res, next) -> {
    var claim = app.db().findById("claims", req.params("id"), Claim.class);
    res.json(claim);
});

app.post("/claims", (req, res, next) -> {
    var claim = req.body(Claim.class);
    var saved = app.db().insert("claims", claim);
    res.status(201).json(saved);
});

// Background jobs — cafeai-jobs module
app.job("daily-digest", Schedule.cron("0 8 * * *"), ctx -> {
    var claims = app.db().query(
        "SELECT * FROM claims WHERE status = ?", "PENDING", Claim.class);
    // full app object available — app.prompt(), app.email(), etc.
});

// Email — cafeai-email module
app.post("/notify", (req, res, next) -> {
    app.email(new EmailMessage()
        .to(req.body("email"))
        .subject("Your claim has been received")
        .template("claim-received", Map.of("claimNumber", "CLM-001")));
    res.json(Map.of("sent", true));
});

app.listen(8080);
```

### Data Access — `cafeai-data`

A fluent, SQL-backed data access API. No ORM, no entity annotations,
no repository interfaces. Java records map cleanly.

```java
app.db(DataSource.pgBouncer(config));

// Find by primary key
Claim claim = app.db().findById("claims", id, Claim.class);

// Query
List<Claim> open = app.db().query(
    "SELECT * FROM claims WHERE status = ?", "OPEN", Claim.class);

// Insert
Claim saved = app.db().insert("claims", newClaim);

// Update
app.db().update("claims", id, Map.of("status", "APPROVED"));

// Delete
app.db().delete("claims", id);
```

### Database Consistency Semantics

Strong consistency is the default. Every `db` call goes to the primary —
the developer gets correct reads without thinking about it.

Eventual consistency is opted into explicitly via a callback. The shape
of the call makes the semantic self-evident — no distributed systems
vocabulary required:

```java
// Strong consistency — returns value directly (default)
// Goes to primary. Reflects all committed writes.
Claim claim = app.db().findById("claims", id, Claim.class);

// Eventual consistency — callback form
// Goes to replica. Developer acknowledged the trade by writing a callback.
app.db().findById("claims", id, Claim.class, claim -> {
    // may not reflect the latest write — the callback shape makes this explicit
});
```

The callback form prevents accidental defeat of eventual consistency.
You cannot call `.join()` on a callback. The contract is enforced by the
API shape, not by documentation or discipline. The wrong choice is also
immediately visible in code review — a callback on a payment operation
stands out.

### Background Jobs — `cafeai-jobs`

Jobs are declared alongside routes and filters on the same `app` object.
`cafe deploy` generates the corresponding k8s CronJob resource. The
developer never writes a manifest.

```java
app.job("daily-digest", Schedule.cron("0 8 * * *"), ctx -> {
    // runs at 8am every day
});

app.job("cache-warm", Schedule.every(Duration.ofMinutes(15)), ctx -> {
    // runs every 15 minutes
});
```

Job handlers have access to the full `app` object — `app.db()`,
`app.prompt()`, `app.email()` — because it is the same object.

### Transactional Email — `cafeai-email`

Email via Resend. SMTP, MX records, and deliverability are invisible.
The `RESEND_API_KEY` is declared in `cafeai.yaml` and delivered by the
platform. The developer never handles it in code.

```java
app.email(new EmailMessage()
    .to("claimant@example.com")
    .subject("Your claim has been received")
    .template("claim-received", Map.of(
        "claimNumber",         "CLM-9821",
        "estimatedResolution", "5-7 business days"
    )));
```

---

## Configuration

CafeStack separates configuration concerns across three clearly owned
layers. Each layer has a single owner, a clear change rate, and a clear
purpose. They do not overlap.

### `cafeai.yaml` — Application Posture

The permanent, environment-agnostic description of the application. Owned
by the developer. Committed to version control. True regardless of where
the application is deployed.

```yaml
# cafeai.yaml
app:
  name: acme-claims
  port: 8080

ai:
  provider: ollama          # dev default — OPENAI_API_KEY switches to OpenAI

database:
  migrations: db/migrations/

auth:
  provider: gotrue

observability:
  strategy: otel

secrets:
  - OPENAI_API_KEY
  - DATABASE_URL
  - RESEND_API_KEY
```

This file describes *what* the application is. It does not describe
*where* it runs or *how many* instances it needs.

### `cafeai.env` — Environment Values

Environment-specific values injected at deploy time. Never committed to
version control. Owned by whoever operates each environment.

```env
# cafeai.env (prod)
DATABASE_URL=postgres://pgbouncer:5432/acme
OPENAI_API_KEY=sk-...
RESEND_API_KEY=re_...
REPLICAS=4
HPA_MIN=2
HPA_MAX=10
OTEL_ENDPOINT=http://otel-collector:4317
```

Environment variables override `cafeai.yaml` values where both exist.
The file is the developer experience. The environment is the operator
experience. They do not conflict.

### `.cafestack/` — Generated Infrastructure

Raw k8s manifests generated by `cafe deploy` from `cafeai.yaml` and
the active environment's values. The developer never edits these files
by hand. The operator can inspect, version, and apply them with plain
kubectl or pipe them into any GitOps pipeline (ArgoCD, Flux) without
any CafeStack involvement.

Namespaced by environment:

```
.cafestack/
  dev/
    compose.yaml          ← Docker Compose — used by cafe dev, not k8s
  test/
    deployment.yaml
    service.yaml
    hpa.yaml
    cronjobs.yaml
  preprod/
    deployment.yaml
    service.yaml
    hpa.yaml
    cronjobs.yaml
    secrets.yaml
  prod/
    deployment.yaml
    service.yaml
    hpa.yaml
    cronjobs.yaml
    secrets.yaml
```

Each environment folder is a complete, self-contained set of manifests
for that environment. `cafe deploy prod` applies `.cafestack/prod/`.
`cafe deploy test` applies `.cafestack/test/`. A GitOps pipeline can
watch any of these folders directly without CafeStack involvement.

The `dev/` folder is the one exception — it contains a Docker Compose
file rather than k8s manifests, because `cafe dev` runs the full
stateless zone locally without a real cluster. The application behaviour
is identical to production: same services, same topology, same wiring.
The orchestration mechanism differs. The developer does not notice.

The `.cafestack/` directory is unapologetically k8s. This is a feature.
Operators already know k8s. Generated manifests are inspectable, standard,
and portable. CafeStack does not invent a proprietary deployment format.

---

## The CLI

### Implementation

The `cafe` CLI is a standalone Go binary. It lives in a separate
repository (`cafestack-cli`) and is distributed independently of the
Java modules. The developer installs it once. It has no runtime
dependency on the CafeAI JVM modules.

Go is the correct implementation language for three reasons:

**Single binary distribution.** Go compiles to a self-contained executable
with no runtime dependency. Installation is one step — download and place
on PATH. Homebrew, a curl installer, and GitHub releases are all standard
Go distribution patterns.

**k8s ecosystem fit.** The entire Kubernetes tooling ecosystem — kubectl,
k3s, k0s, Helm, ArgoCD — is written in Go. The `client-go` library
provides a battle-tested k8s API client. Delegating to kubectl and
generating k8s manifests is idiomatic Go work.

**Fast startup.** A CLI where every command takes 500ms to start is a
bad CLI. Go binaries start in milliseconds.

The CLI lives at: `github.com/cafestack/cafestack-cli`

### Design

The `cafe` CLI is a vocabulary translation layer, not a reimplementation
of kubectl. The developer speaks application language. The CLI translates
to kubectl operations and applies them. A curious developer can pass
`--dry-run` to any command to see the underlying kubectl operations
that would be executed, without running them.

Every `cafe` command is either a thin kubectl delegation or one of a
small number of genuinely new operations that have no kubectl equivalent.

### Command Reference

**`cafe new <name>`**
Scaffolds a new CafeStack project. Creates the project directory,
initialises git, generates `cafeai.yaml`, writes Gradle build files,
creates the `.cafestack/` folder structure, and opens the project in
the IDE.
*No kubectl equivalent — pure scaffolding.*

**`cafe dev`**
Starts the full stateless zone locally using Docker Compose from
`.cafestack/dev/compose.yaml`. Brings up Valkey, pgBouncer, PostgreSQL,
GoTrue, and the OTel collector pre-wired and matching production topology.
Applies pending database migrations on startup. Watches source files and
hot-reloads the application on change.
*No kubectl equivalent — local Docker Compose orchestration.*

**`cafe deploy [env]`**
Generates fresh k8s manifests into `.cafestack/<env>/` from `cafeai.yaml`
and the active environment values, then applies them to the cluster.
Defaults to `prod` if no environment is specified.
```
→  kubectl apply -f .cafestack/prod/
```

**`cafe scale <n> [env]`**
Scales the application ReplicaSet to `n` instances in the target
environment.
```
→  kubectl scale deployment <app-name> --replicas=<n>
```

**`cafe rollback [env]`**
Rolls back the application deployment to the previous revision.
```
→  kubectl rollout undo deployment <app-name>
```

**`cafe logs [env]`**
Tails structured logs from all application containers across all running
instances, labelled by pod.
```
→  kubectl logs -l app=<app-name> --all-containers --follow
```

**`cafe status [env]`**
Reports the health of every component in the stateless zone — pods,
services, HPA state, and replica counts — in application language rather
than raw k8s output.
```
→  kubectl get pods,services,hpa -l app=<app-name>
```

**`cafe db migrate`**
Connects directly to the database and applies all pending SQL migration
files from `db/migrations/` in version order. Reports each migration
applied or skipped.
*No kubectl equivalent — direct database operation.*

**`cafe db replicas <n>`**
Scales the PostgreSQL read replica count to `n` and updates pgBouncer's
routing table to include the new replicas.
```
→  kubectl apply (updated PostgreSQL StatefulSet + pgBouncer ConfigMap)
```

**`cafe doctor [env]`**
Diagnoses the health of the full application stack for the target
environment. Reads the manifests, checks cluster connectivity, validates
that all declared secrets exist and are populated, confirms the database
is reachable through pgBouncer, verifies GoTrue is issuing tokens, checks
the OTel collector is accepting spans, and validates Traefik routing.
Reports findings in plain application language — "your database is
unreachable" not "pod cafeai-pgbouncer-7d9f has CrashLoopBackOff".
*Aggregates multiple kubectl and connectivity checks — no single equivalent.*

**`cafe add handler <name>`**
Scaffolds a new route handler file with the correct package structure
and registers it in the application entry point.
*No kubectl equivalent — pure scaffolding.*

**`cafe open`**
Opens the project in the configured IDE.
*No kubectl equivalent.*

---

## The Irreducible Concerns — Full Inventory

| Concern | CafeStack answer | Developer effort |
|---------|-----------------|-----------------|
| SSL certificates | Traefik — automatic | Zero |
| Load balancing | Traefik — automatic | Zero |
| Service discovery | k8s Services — automatic | Zero |
| Autoscaling | k8s HPA — automatic | Zero |
| Auth token verification | Traefik + GoTrue | Declare protected routes |
| Rate limiting | Traefik | Zero |
| Session management | Valkey — externalized | Zero |
| Distributed cache | Valkey — shared | Zero |
| Connection pooling | pgBouncer — automatic | Zero |
| Read/write routing | pgBouncer — strong default, callback for eventual | One API shape |
| Read replicas | PostgreSQL — one config value | Set a number |
| Schema migrations | Flyway-style — on startup and cafe db migrate | Write SQL |
| Data access | cafeai-data — fluent, no ORM | Write handlers |
| Background jobs | app.job() — k8s CronJob generated | Declare schedule |
| Transactional email | app.email() via Resend | Declare message |
| Secrets management | k8s Secrets + external-secrets-operator | Declare names |
| Observability | OTel sidecar — automatic | Zero |
| AI primitives | CafeAI — prompt, vision, audio, TTS | Already known |
| Guardrails | CafeAI — jailbreak, PII, regulatory | Already known |
| Horizontal scaling | k8s ReplicaSet — cafe scale N | One command |
| Local dev environment | cafe dev — one command | Zero |
| Production deployment | cafe deploy — one command | Zero |
| Stack diagnosis | cafe doctor | One command |

---

## The Graduation Path

A developer starts with raw CafeAI — no infrastructure, just the framework.
When they are ready for production, they do not rewrite anything. They run
`cafe deploy` and the same code is now running inside the full CafeStack
platform. The `app` object did not change. The infrastructure appeared
around it.

This is the clearest expression of CafeStack's value proposition: the
developer experience does not change between prototype and production.
The platform absorbs the difference.

---

## What CafeStack Is Not

**CafeStack is not a framework.** It is a platform. You work within it or
you work outside it.

**CafeStack does not compete with Quarkus or Spring Boot.** Those frameworks
optimise for flexibility and enterprise feature sets. CafeStack optimises
for the developer who wants to build an application, not configure a
platform. The audiences are different.

**CafeStack does not hide infrastructure from curious developers.** A
developer who wants to inspect the generated k8s manifests, run kubectl
directly, or examine the Traefik config is free to do so. CafeStack makes
it unnecessary, not impossible.

**CafeStack does not invent a proprietary deployment format.** The generated
infrastructure files are standard k8s YAML. They work with any kubectl,
any GitOps pipeline, any k8s-compatible cluster. CafeStack generates them.
It does not own them.

**CafeStack is not for every use case.** It is for the developer building
a web application with conventional concerns — HTTP, data, auth, some AI —
who wants to spend their time on the application, not the infrastructure.

**CafeStack does not guarantee the availability of third-party services.**
Resend is the default email provider because it is excellent, but it is a
managed external service — not infrastructure CafeStack operates. The
`cafeai-email` module's `EmailProvider` SPI allows substitution with any
provider. The opinionated default and the swappable implementation are not
in conflict — the SPI is how CafeStack stays honest about the boundary
between what it controls and what it does not.

---

## Repositories

CafeStack spans two repositories with clearly separated concerns.

**`cafeai`** — the existing Java monorepo. Contains all CafeAI modules
including the new `cafeai-data`, `cafeai-jobs`, and `cafeai-email`
modules. Published to Maven Central. The Java modules have no dependency
on the CLI and can be used independently.

**`cafestack-cli`** — the Go CLI binary. Contains the `cafe` command and
all subcommands. Reads `cafeai.yaml`, generates k8s manifests, delegates
to kubectl. Distributed as a standalone binary via Homebrew, GitHub
releases, and a curl installer. Has no compile-time or runtime dependency
on the Java modules.

The two repositories can be developed in parallel. The CLI's only inputs
are `cafeai.yaml`, environment variables, and a reachable k8s cluster.
None of those require the Java modules to be finished first.

---

## Roadmap

The roadmap has two parallel tracks that converge when both are ready.

### Track A — Java Modules (`cafeai` repository)

**Phase A1 — `cafeai-data`**
- `DataProvider` SPI added to `cafeai-core` alongside existing SPIs
- `DbHandle`, `DataSource` domain objects in `io.cafeai.core.data`
- `cafeai-data` module implementing `DataProvider` via ServiceLoader
- `DataSource.pgBouncer(config)` integration
- Fluent API: `findById`, `query`, `insert`, `update`, `delete`
- Strong consistency default (primary routing)
- Eventual consistency via callback (replica routing)
- Java record mapping — no ORM, no annotations
- HikariCP connection pool (local dev), pgBouncer (production)
- Schema migration runner (Flyway-style), applied on `app.listen()`

**Phase A2 — `cafeai-jobs` and `cafeai-email`**
- `JobScheduler` SPI and `Schedule` domain objects in `cafeai-core`
- `EmailProvider` SPI and `EmailMessage` domain object in `cafeai-core`
- `cafeai-jobs` module: `app.job()`, `Schedule.cron()`, `Schedule.every()`
- `cafeai-email` module: `app.email()` via Resend integration
- `Auth.require().on(path)` filter wired to GoTrue JWT verification

### Track B — CLI (`cafestack-cli` repository)

**Phase B1 — Foundation**
- Go project scaffold, command structure, `--dry-run` flag
- `cafeai.yaml` parser and validation
- `cafeai.env` loader with environment variable override semantics
- `cafe new` — project scaffolding, git init, IDE config generation
- `.cafestack/` folder structure and k8s manifest generation
  (Deployment, Service, HPA, CronJob, Secrets) per environment

**Phase B2 — Cluster Operations**
- `cafe deploy [env]` — manifest generation + `kubectl apply`
- `cafe scale <n> [env]` — ReplicaSet scaling
- `cafe rollback [env]` — deployment rollback
- `cafe logs [env]` — multi-pod log tailing
- `cafe status [env]` — application-language health summary
- `cafe db replicas <n>` — PostgreSQL replica scaling

**Phase B3 — Developer Experience**
- `cafe dev` — Docker Compose orchestration of full local stateless zone
- `cafe db migrate` — direct database migration runner
- `cafe doctor [env]` — plain-language full-stack diagnosis
- `cafe add handler <name>` — route handler scaffolding
- `cafe open` — IDE integration
- Homebrew formula, GitHub release pipeline, curl installer

### Convergence

Once both tracks are stable and integrated the platform is feature-complete
for general use. The following phases build on that foundation.

**Phase C1 — Production Hardening**
- Managed PostgreSQL integration (RDS, Neon, Supabase)
- Vault integration for secrets in regulated environments
- Multi-region k8s configuration
- `cafe doctor` extended with managed service connectivity checks
- Performance benchmarks and load testing across the full stateless zone
- Public documentation site — architecture guide, module reference,
  CLI reference, deployment guide

**Phase C2 — 0.2.0 Maven Central Release**
- All CafeStack Java modules (`cafeai-data`, `cafeai-jobs`, `cafeai-email`)
  released alongside existing CafeAI modules
- ReAct agents (`cafeai-agents`) and orchestration from ROADMAP-17
- PgVector implementation (`cafeai-rag`)
- Real OTel spans with GenAI semantic conventions (`cafeai-observability`)
- Hybrid retrieval — BM25 + dense (`cafeai-rag`)
- `CHANGELOG.md` and `MIGRATION.md` covering all changes from 0.1.0
- All modules visible at `search.maven.org/artifact/io.cafeai`

**Phase C3 — Community**
- CafeStack project templates (CRUD app, document intelligence,
  API gateway, AI-powered support)
- Plugin system for non-prescribed component swaps
- CafeStack registry for shared handlers and filters
- Conference talks and blog series published
- Public GitHub repositories open-sourced
