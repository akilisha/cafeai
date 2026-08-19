# MILESTONE-19 — cafeai-jobs and cafeai-email

**Current Status:** 🔴 Not Started

| Phase | Description | Status |
|-------|-------------|--------|
| 1 | `JobScheduler` SPI and `Schedule` domain objects in `cafeai-core` | 🔴 |
| 2 | `app.job()` entry point | 🔴 |
| 3 | `cafeai-jobs` module scaffold | 🔴 |
| 4 | In-process scheduler (local dev) | 🔴 |
| 5 | k8s CronJob manifest generation | 🔴 |
| 6 | Job tests | 🔴 |
| 7 | `EmailProvider` SPI and `EmailMessage` in `cafeai-core` | 🔴 |
| 8 | `app.email()` entry point | 🔴 |
| 9 | `cafeai-email` module scaffold | 🔴 |
| 10 | Resend `EmailProvider` implementation | 🔴 |
| 11 | Email template support | 🔴 |
| 12 | Email tests | 🔴 |

---

## Phase 1 — `JobScheduler` SPI and `Schedule` Domain Objects

**Status:** 🔴 Not Started

### Acceptance Criteria
- [ ] `JobScheduler` interface added to `io.cafeai.core.spi`
- [ ] `JobScheduler` declares `void schedule(JobDefinition job)`
      and `void shutdown()`
- [ ] `JobDefinition` record in `io.cafeai.core.jobs` carrying
      `name`, `schedule`, and `handler`
- [ ] `Schedule` class in `io.cafeai.core.jobs` with two factory methods:
      `Schedule.cron(String expression)` and
      `Schedule.every(Duration interval)`
- [ ] `Schedule.cron()` validates the expression at construction time —
      invalid expression throws `InvalidScheduleException` with the
      offending expression in the message
- [ ] `JobHandler` functional interface: `void run(JobContext ctx)`
- [ ] `JobContext` carries `jobName()`, `scheduledAt()`, `app()` reference
- [ ] `./gradlew :cafeai-core:compileJava` — zero errors, zero warnings

### SPI Contract
```java
package io.cafeai.core.spi;

public interface JobScheduler {
    void schedule(JobDefinition job);
    void shutdown();
}
```

### Notes
<!-- Add implementation notes here -->

---

## Phase 2 — `app.job()` Entry Point

**Status:** 🔴 Not Started

### Acceptance Criteria
- [ ] `CafeAI.job(String name, Schedule schedule, JobHandler handler)`
      registers a background job
- [ ] Jobs registered before `app.listen()` — registration after listen
      throws `IllegalStateException`
- [ ] Duplicate job names throw `IllegalArgumentException` with the
      offending name in the message
- [ ] `app.db()`, `app.prompt()`, `app.email()`, and all other `app`
      primitives are accessible inside job handlers via `JobContext.app()`
- [ ] `./gradlew :cafeai-core:test` — registration tests pass

### Usage
```java
app.job("daily-digest", Schedule.cron("0 8 * * *"), ctx -> {
    var claims = ctx.app().db().query(
        "SELECT * FROM claims WHERE status = ?", "PENDING", Claim.class);
    // process claims
});
```

### Notes
<!-- Add implementation notes here -->

---

## Phase 3 — `cafeai-jobs` Module Scaffold

**Status:** 🔴 Not Started

### Acceptance Criteria
- [ ] `cafeai-jobs` module created in the Gradle multi-project build
- [ ] `build.gradle` declares dependency on `cafeai-core`
- [ ] Package structure: `io.cafeai.jobs` root,
      `io.cafeai.jobs.scheduler` for in-process scheduler,
      `io.cafeai.jobs.k8s` for CronJob manifest generation
- [ ] `META-INF/services/io.cafeai.core.spi.JobScheduler` registered
- [ ] `./gradlew :cafeai-jobs:compileJava` — zero errors

### Notes
<!-- Add implementation notes here -->

---

## Phase 4 — In-Process Scheduler (Local Dev)

**Status:** 🔴 Not Started

### Acceptance Criteria
- [ ] `InProcessJobScheduler` implements `JobScheduler` in
      `io.cafeai.jobs.scheduler`
- [ ] Cron expressions parsed and scheduled using a lightweight cron
      library (e.g. `cron-utils` or equivalent)
- [ ] `Schedule.every(Duration)` implemented using
      `ScheduledExecutorService` on a virtual thread pool
- [ ] Jobs run on virtual threads — no blocking of the main server thread
- [ ] Job exceptions are caught, logged via `ObserveBridge`, and do not
      crash the scheduler
- [ ] `shutdown()` waits for any in-flight job to complete before
      returning (max wait: 30s)
- [ ] `./gradlew :cafeai-jobs:compileJava` — zero errors

### Notes
The in-process scheduler is the local dev implementation. In a CafeStack
production deployment, jobs are k8s CronJobs and do not run in-process.
The developer does not need to know which mode is active.

---

## Phase 5 — k8s CronJob Manifest Generation

**Status:** 🔴 Not Started

### Acceptance Criteria
- [ ] `CronJobManifestGenerator` in `io.cafeai.jobs.k8s` generates a
      k8s CronJob YAML manifest for each registered `JobDefinition`
- [ ] Generated manifest references the application container image
- [ ] Cron schedule from `Schedule.cron()` maps directly to
      `spec.schedule` in the CronJob manifest
- [ ] `Schedule.every(Duration)` is converted to the nearest valid cron
      expression — sub-minute intervals throw `UnsupportedScheduleException`
      with a clear message
- [ ] Manifests written to `.cafestack/<env>/cronjobs.yaml` when
      `cafe deploy` is invoked (CLI concern — generator just produces the YAML)
- [ ] `./gradlew :cafeai-jobs:test` — manifest output matches expected YAML

### Generated Manifest Shape
```yaml
apiVersion: batch/v1
kind: CronJob
metadata:
  name: <app-name>-<job-name>
spec:
  schedule: "0 8 * * *"
  jobTemplate:
    spec:
      template:
        spec:
          containers:
            - name: <job-name>
              image: <app-image>
              args: ["--job", "<job-name>"]
          restartPolicy: OnFailure
```

### Notes
<!-- Add implementation notes here -->

---

## Phase 6 — Job Tests

**Status:** 🔴 Not Started

### Acceptance Criteria
- [ ] In-process scheduler: job runs at correct interval (`Schedule.every`)
- [ ] In-process scheduler: cron job fires at correct time (mocked clock)
- [ ] Job exception does not crash the scheduler — next execution proceeds
- [ ] `shutdown()` waits for in-flight job before returning
- [ ] Duplicate job name registration throws at registration time
- [ ] Invalid cron expression throws at `Schedule.cron()` call time
- [ ] CronJob manifest: correct YAML generated for cron schedule
- [ ] CronJob manifest: `Schedule.every(Duration.ofMinutes(15))` converts
      correctly to `*/15 * * * *`
- [ ] `./gradlew :cafeai-jobs:test` — all tests pass

### Notes
<!-- Add implementation notes here -->

---

## Phase 7 — `EmailProvider` SPI and `EmailMessage` Domain Object

**Status:** 🔴 Not Started

### Acceptance Criteria
- [ ] `EmailProvider` interface added to `io.cafeai.core.spi`
- [ ] `EmailProvider` declares `void send(EmailMessage message)`
- [ ] `EmailMessage` record added to `io.cafeai.core.email` with
      fluent builder: `.to()`, `.subject()`, `.body()`, `.template()`,
      `.from()` (optional override)
- [ ] `EmailMessage.template(String name, Map<String, Object> vars)`
      stores template name and variables — rendering is provider-specific
- [ ] `EmailMessage` validates that either `body()` or `template()` is
      set — not both, not neither — at build time
- [ ] `./gradlew :cafeai-core:compileJava` — zero errors, zero warnings

### SPI Contract
```java
package io.cafeai.core.spi;

public interface EmailProvider {
    void send(EmailMessage message);
}
```

### Notes
<!-- Add implementation notes here -->

---

## Phase 8 — `app.email()` Entry Point

**Status:** 🔴 Not Started

### Acceptance Criteria
- [ ] `CafeAI.email(EmailMessage message)` sends a message via the
      registered `EmailProvider`
- [ ] Calling `app.email()` with no `EmailProvider` on the classpath
      throws `IllegalStateException` with a message naming the missing
      module (`cafeai-email`)
- [ ] `app.email()` is available inside route handlers, job handlers,
      and filter chains
- [ ] Send is fire-and-forget on a virtual thread — does not block the
      calling thread
- [ ] Failures are caught and routed to `ObserveBridge` as an error event —
      they do not propagate as exceptions to the caller by default
- [ ] `./gradlew :cafeai-core:test` — registration and missing-provider
      tests pass

### Usage
```java
app.email(new EmailMessage()
    .to("claimant@example.com")
    .subject("Your claim has been received")
    .template("claim-received", Map.of("claimNumber", "CLM-9821")));
```

### Notes
<!-- Add implementation notes here -->

---

## Phase 9 — `cafeai-email` Module Scaffold

**Status:** 🔴 Not Started

### Acceptance Criteria
- [ ] `cafeai-email` module created in the Gradle multi-project build
- [ ] `build.gradle` declares dependency on `cafeai-core`
- [ ] Package structure: `io.cafeai.email` root,
      `io.cafeai.email.resend` for Resend implementation,
      `io.cafeai.email.template` for template rendering
- [ ] `META-INF/services/io.cafeai.core.spi.EmailProvider` registered
- [ ] `./gradlew :cafeai-email:compileJava` — zero errors

### Notes
<!-- Add implementation notes here -->

---

## Phase 10 — Resend `EmailProvider` Implementation

**Status:** 🔴 Not Started

### Acceptance Criteria
- [ ] `ResendEmailProvider` implements `EmailProvider`
      in `io.cafeai.email.resend`
- [ ] Uses Helidon's HTTP client (already a dependency) to call the
      Resend API — no additional HTTP client dependency introduced
- [ ] `RESEND_API_KEY` read from environment — not from application code
- [ ] `ResendEmailProvider` is the auto-discovered default when
      `cafeai-email` is on the classpath and `RESEND_API_KEY` is set
- [ ] Missing `RESEND_API_KEY` throws `EmailConfigurationException` at
      startup — not at send time
- [ ] Response errors (4xx, 5xx) from Resend are wrapped in
      `EmailDeliveryException` and routed to `ObserveBridge`
- [ ] `./gradlew :cafeai-email:compileJava` — zero errors

### Notes
Resend is the default. The `EmailProvider` SPI allows substitution with
any provider — Postmark, SendGrid, SMTP relay — without changing
application code.

---

## Phase 11 — Email Template Support

**Status:** 🔴 Not Started

### Acceptance Criteria
- [ ] `TemplateRenderer` in `io.cafeai.email.template` renders a named
      template with a variable map to a plain string (HTML or text)
- [ ] Templates discovered from classpath under `email/templates/`
      (e.g. `email/templates/claim-received.html`)
- [ ] Variable substitution uses `{{variableName}}` syntax — no external
      template engine dependency introduced
- [ ] Missing template throws `TemplateNotFoundException` with the
      template name in the message
- [ ] Rendered body passed to `EmailProvider.send()` — provider receives
      plain HTML/text, not a template reference
- [ ] `./gradlew :cafeai-email:test` — rendering tests for substitution,
      missing variable (left as empty string), missing template

### Template Format
```html
<!-- email/templates/claim-received.html -->
<h1>Your claim has been received</h1>
<p>Claim number: {{claimNumber}}</p>
<p>Estimated resolution: {{estimatedResolution}}</p>
```

### Notes
Deliberately simple. A richer template engine (Freemarker, Mustache) can
be wired by implementing `EmailProvider` directly with the desired engine.

---

## Phase 12 — Email Tests

**Status:** 🔴 Not Started

### Acceptance Criteria
- [ ] `ResendEmailProvider` tested with a WireMock stub of the Resend API
- [ ] Successful send: 200 response from stub, no exception
- [ ] API error: 4xx response from stub, `EmailDeliveryException` routed
      to `ObserveBridge`
- [ ] Missing `RESEND_API_KEY`: `EmailConfigurationException` at startup
- [ ] Template rendering: substitution correct, missing template throws,
      missing variable leaves placeholder empty
- [ ] `app.email()` with no provider on classpath: `IllegalStateException`
- [ ] Fire-and-forget: calling thread not blocked by send
- [ ] `./gradlew :cafeai-email:test` — all tests pass
- [ ] `./gradlew clean build` — BUILD SUCCESSFUL, zero warnings

### Notes
WireMock is already a common test dependency in the Java ecosystem and
adds no meaningful weight to the test suite.

---

## Completion Definition

MILESTONE-19 is **complete** when:

1. All 12 phases show ✅ Complete
2. `./gradlew clean build` — BUILD SUCCESSFUL, zero warnings
3. `./gradlew javadoc` — zero warnings on `cafeai-jobs` and `cafeai-email`
4. `./gradlew :cafeai-jobs:test` — all tests pass
5. `./gradlew :cafeai-email:test` — all tests pass
6. `app.job()` declared, fires in-process locally, generates CronJob manifest
7. `app.email()` sends via Resend with template rendering end-to-end

**What success looks like:**

```java
var app = CafeAI.create();

app.ai("tutor",  OpenAI.gpt4o());
app.db(DataSource.pgBouncer(config));
app.guard(GuardRail.pii());
app.observe(ObserveStrategy.otel());

// Scheduled job — runs daily, uses full app object
app.job("claim-digest", Schedule.cron("0 8 * * *"), ctx -> {
    var pending = ctx.app().db().query(
        "SELECT * FROM claims WHERE status = ?", "PENDING", Claim.class);
    pending.forEach(claim ->
        ctx.app().email(new EmailMessage()
            .to(claim.adjusterEmail())
            .subject("Pending claim: " + claim.claimNumber())
            .template("claim-digest", Map.of("claim", claim))));
});

// Route handler — sends confirmation email on new claim
app.post("/claims", (req, res, next) -> {
    var claim = app.db().insert("claims", req.body(Claim.class));
    app.email(new EmailMessage()
        .to(claim.claimantEmail())
        .subject("Claim received")
        .template("claim-received", Map.of("claimNumber", claim.claimNumber())));
    res.status(201).json(claim);
});

app.listen(8080);
```
