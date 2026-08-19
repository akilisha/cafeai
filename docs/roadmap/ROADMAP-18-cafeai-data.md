# ROADMAP-18 — cafeai-data: Database Access Module

> Adds first-class database access to CafeAI as a new `cafeai-data` module.
> Follows the established SPI pattern. Introduces the `db` primitive to the
> `app` object. Lays the data access foundation for CafeStack deployments.
>
> This roadmap follows ROADMAP-17 (framework completeness) and runs in
> parallel with ROADMAP-19 (cafeai-jobs and cafeai-email).

---

## Sequencing

```
ROADMAP-17 ✅
    ↓
ROADMAP-18 (cafeai-data)  ←→  ROADMAP-19 (cafeai-jobs, cafeai-email)
    ↓
CafeStack Phase A complete
    ↓
0.2.0 on Maven Central (with ROADMAP-17 items)
```

---

## What ROADMAP-18 delivers

### `app.db()` — The data access primitive

```java
var app = CafeAI.create();
app.db(DataSource.pgBouncer(config));

// Find by primary key — strong consistency (default, goes to primary)
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

// Eventual consistency — callback form (goes to replica)
app.db().findById("claims", id, Claim.class, claim -> {
    // may not reflect the latest write — callback shape makes this explicit
});
```

### Schema migrations — applied automatically on startup

```
db/migrations/
  V1__create_claims.sql
  V2__add_adjuster_index.sql
  V3__add_audit_log.sql
```

Migrations are applied in version order on `app.listen()`. Already-applied
migrations are skipped. No external migration tool required.

### `DataSource` factory

```java
// Local dev — HikariCP direct connection
app.db(DataSource.direct(config));

// CafeStack production — via pgBouncer
app.db(DataSource.pgBouncer(config));
```

---

## Phase inventory

| Phase | Description |
|-------|-------------|
| 1 | `DataProvider` SPI in `cafeai-core` |
| 2 | `DbHandle` and `DataSource` domain objects |
| 3 | `app.db()` entry point |
| 4 | `cafeai-data` module scaffold |
| 5 | `DataSource.direct()` — HikariCP implementation |
| 6 | Fluent query API — `findById`, `query`, `insert`, `update`, `delete` |
| 7 | Java record mapping |
| 8 | Strong consistency default (primary routing) |
| 9 | Eventual consistency via callback (replica routing) |
| 10 | Schema migration runner |
| 11 | `DataSource.pgBouncer()` — pgBouncer-aware implementation |
| 12 | Integration tests (Testcontainers) |

---

## What this roadmap does NOT cover

- ORM or entity annotation mapping — no Hibernate, no JPA
- Transaction management API — transactions are scoped to individual
  operations for now; multi-statement transactions are a future concern
- NoSQL or document store support — PostgreSQL only
- Multi-tenancy — schema-per-tenant is a future concern
- Query builder DSL — raw SQL with positional parameters only

CafeStack applications that need the above should wire their own client
directly. The `DataProvider` SPI leaves the door open for future
implementations.

---

## What comes next

ROADMAP-19 runs in parallel. Both tracks feed into the CafeStack
platform hardening phase and the 0.2.0 Maven Central release.
