# ROADMAP-19 — cafeai-jobs and cafeai-email

> Adds background job scheduling and transactional email to CafeAI as two
> new modules: `cafeai-jobs` and `cafeai-email`. Both follow the established
> SPI pattern. Both surface as new primitives on the `app` object.
>
> Runs in parallel with ROADMAP-18 (cafeai-data). Neither roadmap depends
> on the other — both feed into the CafeStack platform hardening phase.

---

## Sequencing

```
ROADMAP-17 ✅
    ↓
ROADMAP-18 (cafeai-data)  ←→  ROADMAP-19 (cafeai-jobs, cafeai-email)
    ↓
CafeStack Phase A complete
    ↓
0.2.0 on Maven Central
```

---

## What ROADMAP-19 delivers

### `app.job()` — Background job scheduling

```java
var app = CafeAI.create();

// Cron schedule
app.job("daily-digest", Schedule.cron("0 8 * * *"), ctx -> {
    var claims = app.db().query(
        "SELECT * FROM claims WHERE status = ?", "PENDING", Claim.class);
    claims.forEach(claim ->
        app.email(new EmailMessage()
            .to(claim.adjusterEmail())
            .subject("Pending claim requires attention")
            .template("claim-digest", Map.of("claim", claim))));
});

// Fixed interval
app.job("cache-warm", Schedule.every(Duration.ofMinutes(15)), ctx -> {
    // full app object available — app.db(), app.prompt(), app.email()
});
```

In a CafeStack deployment, `cafe deploy` generates a k8s CronJob resource
for each declared job. In local dev, jobs run in-process on a scheduler
thread. The developer does not notice the difference.

### `app.email()` — Transactional email

```java
app.email(new EmailMessage()
    .to("claimant@example.com")
    .subject("Your claim has been received")
    .template("claim-received", Map.of(
        "claimNumber",         "CLM-9821",
        "estimatedResolution", "5-7 business days"
    )));

// Plain text — no template
app.email(new EmailMessage()
    .to("adjuster@example.com")
    .subject("Urgent: claim requires review")
    .body("Claim CLM-9821 has been flagged for manual review."));
```

Default provider is Resend. The `EmailProvider` SPI allows substitution
with any provider — the developer's code does not change.

---

## Phase inventory

| Phase | Description |
|-------|-------------|
| 1 | `JobScheduler` SPI and `Schedule` domain objects in `cafeai-core` |
| 2 | `app.job()` entry point |
| 3 | `cafeai-jobs` module scaffold |
| 4 | In-process scheduler (local dev) |
| 5 | k8s CronJob manifest generation |
| 6 | Job tests |
| 7 | `EmailProvider` SPI and `EmailMessage` in `cafeai-core` |
| 8 | `app.email()` entry point |
| 9 | `cafeai-email` module scaffold |
| 10 | Resend `EmailProvider` implementation |
| 11 | Email template support |
| 12 | Email tests |

---

## What this roadmap does NOT cover

- Message queue integration (Kafka, NATS, RabbitMQ) — async messaging
  is a distinct concern and a future roadmap
- Email receipt / inbound email handling — send only
- Email attachment support — body and templates only
- Job result storage or retry policies — fire-and-forget for now
- Distributed job locking — single-instance job execution only for now;
  leader election is a future concern

---

## What comes next

ROADMAP-18 and ROADMAP-19 complete CafeStack's Track A (Java modules).
Both feed into the CafeStack CLI (Track B) and the 0.2.0 Maven Central
release alongside the ROADMAP-17 items.
