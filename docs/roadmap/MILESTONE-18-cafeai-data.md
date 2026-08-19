# MILESTONE-18 — cafeai-data: Database Access Module

**Current Status:** 🔴 Not Started

| Phase | Description | Status |
|-------|-------------|--------|
| 1 | `DataProvider` SPI in `cafeai-core` | 🔴 |
| 2 | `DbHandle` and `DataSource` domain objects | 🔴 |
| 3 | `app.db()` entry point | 🔴 |
| 4 | `cafeai-data` module scaffold | 🔴 |
| 5 | `DataSource.direct()` — HikariCP implementation | 🔴 |
| 6 | Fluent query API — `findById`, `query`, `insert`, `update`, `delete` | 🔴 |
| 7 | Java record mapping | 🔴 |
| 8 | Strong consistency default (primary routing) | 🔴 |
| 9 | Eventual consistency via callback (replica routing) | 🔴 |
| 10 | Schema migration runner | 🔴 |
| 11 | `DataSource.pgBouncer()` — pgBouncer-aware implementation | 🔴 |
| 12 | Integration tests (Testcontainers) | 🔴 |

---

## Phase 1 — `DataProvider` SPI in `cafeai-core`

**Status:** 🔴 Not Started

### Acceptance Criteria
- [ ] `DataProvider` interface added to `io.cafeai.core.spi` alongside
      `MemoryStrategyProvider`, `GuardRailProvider`, and existing SPIs
- [ ] `DataProvider` declares `DbHandle handle()` and `void close()`
- [ ] `CafeAIRegistry` gains `registerDataProvider(DataProvider)` method
- [ ] `./gradlew :cafeai-core:compileJava` — zero errors, zero warnings
- [ ] `./gradlew :cafeai-core:javadoc` — zero warnings

### SPI Contract
```java
package io.cafeai.core.spi;

public interface DataProvider {
    DbHandle handle();
    void close();
}
```

### Notes
<!-- Add implementation notes here -->

---

## Phase 2 — `DbHandle` and `DataSource` Domain Objects

**Status:** 🔴 Not Started

### Acceptance Criteria
- [ ] `DbHandle` interface added to `io.cafeai.core.data`
- [ ] `DbHandle` declares `findById`, `query`, `insert`, `update`, `delete`
      and the callback-form `findById` for eventual consistency
- [ ] `DataSource` class added to `io.cafeai.core.data` as a factory
- [ ] `DataSource.direct(DataSourceConfig)` factory method declared
- [ ] `DataSource.pgBouncer(DataSourceConfig)` factory method declared
- [ ] `DataSourceConfig` builder with `host`, `port`, `database`,
      `username`, `password`, `maxPoolSize` fields
- [ ] All types are immutable records or interfaces — no mutable state
- [ ] `./gradlew :cafeai-core:compileJava` — zero errors, zero warnings

### Domain Objects
```java
package io.cafeai.core.data;

public interface DbHandle {
    <T> T findById(String table, Object id, Class<T> type);
    <T> List<T> query(String sql, Class<T> type, Object... params);
    <T> T insert(String table, T entity);
    void update(String table, Object id, Map<String, Object> fields);
    void delete(String table, Object id);

    // Eventual consistency — callback form, routes to replica
    <T> void findById(String table, Object id, Class<T> type, Consumer<T> callback);
}
```

### Notes
<!-- Add implementation notes here -->

---

## Phase 3 — `app.db()` Entry Point

**Status:** 🔴 Not Started

### Acceptance Criteria
- [ ] `CafeAI.db(DataSource source)` registers the data source
- [ ] `CafeAI.db()` returns the active `DbHandle`
- [ ] Calling `app.db()` before `app.db(source)` throws `IllegalStateException`
      with a clear message naming the missing registration
- [ ] `app.db()` is available inside route handlers, job handlers, and
      filter chains — the same `DbHandle` instance in all contexts
- [ ] `./gradlew :cafeai-core:test` — all tests pass

### Usage
```java
var app = CafeAI.create();
app.db(DataSource.pgBouncer(config));

app.get("/claims/:id", (req, res, next) -> {
    var claim = app.db().findById("claims", req.params("id"), Claim.class);
    res.json(claim);
});
```

### Notes
<!-- Add implementation notes here -->

---

## Phase 4 — `cafeai-data` Module Scaffold

**Status:** 🔴 Not Started

### Acceptance Criteria
- [ ] `cafeai-data` module created in the Gradle multi-project build
- [ ] `build.gradle` declares dependency on `cafeai-core`
- [ ] `build.gradle` declares HikariCP dependency
- [ ] Package structure: `io.cafeai.data` root,
      `io.cafeai.data.hikari` for HikariCP implementation,
      `io.cafeai.data.pgbouncer` for pgBouncer-aware implementation,
      `io.cafeai.data.mapping` for record mapping,
      `io.cafeai.data.migration` for schema migrations
- [ ] `META-INF/services/io.cafeai.core.spi.DataProvider` registered
- [ ] `./gradlew :cafeai-data:compileJava` — zero errors

### Notes
<!-- Add implementation notes here -->

---

## Phase 5 — `DataSource.direct()` — HikariCP Implementation

**Status:** 🔴 Not Started

### Acceptance Criteria
- [ ] `HikariDataProvider` implements `DataProvider` in `io.cafeai.data.hikari`
- [ ] `DataSource.direct(config)` returns a `HikariDataProvider`
- [ ] HikariCP pool configured with sensible defaults:
      `maximumPoolSize=10`, `connectionTimeout=30s`, `idleTimeout=600s`
- [ ] `DataSourceConfig` values override all defaults
- [ ] Pool is closed cleanly on `DataProvider.close()`
- [ ] `./gradlew :cafeai-data:compileJava` — zero errors

### Notes
This is the local dev implementation. `DataSource.pgBouncer()` is the
production implementation and comes in Phase 11.

---

## Phase 6 — Fluent Query API

**Status:** 🔴 Not Started

### Acceptance Criteria
- [ ] `findById(table, id, type)` executes `SELECT * FROM <table> WHERE id = ?`
- [ ] `query(sql, type, params...)` executes arbitrary parameterised SQL
- [ ] `insert(table, entity)` executes `INSERT INTO <table> (...) VALUES (?...)`
      and returns the persisted entity with any database-generated fields
- [ ] `update(table, id, fields)` executes
      `UPDATE <table> SET field=? WHERE id=?` for all provided fields
- [ ] `delete(table, id)` executes `DELETE FROM <table> WHERE id=?`
- [ ] All methods throw `DataAccessException` (unchecked) on SQL error,
      wrapping the underlying `SQLException` with the offending SQL
- [ ] `./gradlew :cafeai-data:test` — unit tests for all five operations

### Notes
<!-- Add implementation notes here -->

---

## Phase 7 — Java Record Mapping

**Status:** 🔴 Not Started

### Acceptance Criteria
- [ ] `ResultSetMapper` in `io.cafeai.data.mapping` maps `ResultSet` rows
      to Java records using reflection on record components
- [ ] Column names matched to record component names
      (snake_case → camelCase conversion applied automatically)
- [ ] `@Column("name")` annotation available for explicit column mapping
- [ ] Null columns map to `null` for reference types, throw for primitives
- [ ] Supported types: `String`, `int`/`Integer`, `long`/`Long`,
      `double`/`Double`, `boolean`/`Boolean`, `LocalDate`, `LocalDateTime`,
      `UUID`, `BigDecimal`
- [ ] `./gradlew :cafeai-data:test` — mapping tests for each supported type,
      snake_case conversion, explicit `@Column`, null handling

### Notes
<!-- Add implementation notes here -->

---

## Phase 8 — Strong Consistency Default

**Status:** 🔴 Not Started

### Acceptance Criteria
- [ ] All synchronous `DbHandle` operations use the primary connection pool
- [ ] "Primary" is the connection configured via `DataSource.direct()` or
      `DataSource.pgBouncer()` — no additional configuration required
- [ ] Strong consistency is the default — the developer does nothing to
      opt in, and cannot accidentally opt out via the synchronous API
- [ ] `./gradlew :cafeai-data:test` — connection routing verified

### Notes
<!-- Add implementation notes here -->

---

## Phase 9 — Eventual Consistency via Callback

**Status:** 🔴 Not Started

### Acceptance Criteria
- [ ] `DbHandle.findById(table, id, type, Consumer<T> callback)` routes
      to the replica connection pool when one is configured
- [ ] When no replica is configured, callback form falls back to primary
      silently — no error, no configuration required
- [ ] Callback is invoked on a virtual thread — does not block the
      calling thread
- [ ] `DataSourceConfig` gains `replicaHost` and `replicaPort` fields
- [ ] `DataSource.pgBouncer(config)` uses pgBouncer's read-only port
      for replica routing when `replicaHost` is configured
- [ ] `./gradlew :cafeai-data:test` — callback invoked, replica routing
      verified, fallback to primary when no replica configured

### Consistency Contract
```java
// Strong — synchronous, primary, blocks until result available
Claim claim = app.db().findById("claims", id, Claim.class);

// Eventual — callback, replica when available, virtual thread
app.db().findById("claims", id, Claim.class, claim -> {
    // arrives asynchronously, may not reflect the latest write
});
```

### Notes
<!-- Add implementation notes here -->

---

## Phase 10 — Schema Migration Runner

**Status:** 🔴 Not Started

### Acceptance Criteria
- [ ] `MigrationRunner` in `io.cafeai.data.migration` applied on `app.listen()`
- [ ] Migrations discovered from the path declared in `cafeai.yaml`
      under `database.migrations` (default: `db/migrations/`)
- [ ] Migration files follow `V{n}__{description}.sql` naming convention
- [ ] Applied migrations recorded in `cafeai_migrations` table
      (created automatically on first run)
- [ ] Already-applied migrations skipped without error
- [ ] Out-of-order migrations throw `MigrationOrderException` with the
      offending filename
- [ ] Checksum validation — a previously applied migration whose file
      content has changed throws `MigrationChecksumException`
- [ ] `./gradlew :cafeai-data:test` — first run applies all, second run
      skips all, out-of-order detected, checksum violation detected

### Migration Tracking Schema
```sql
CREATE TABLE IF NOT EXISTS cafeai_migrations (
    version     INTEGER PRIMARY KEY,
    description TEXT    NOT NULL,
    filename    TEXT    NOT NULL,
    checksum    TEXT    NOT NULL,
    applied_at  TIMESTAMPTZ DEFAULT NOW()
);
```

### Notes
<!-- Add implementation notes here -->

---

## Phase 11 — `DataSource.pgBouncer()` Implementation

**Status:** 🔴 Not Started

### Acceptance Criteria
- [ ] `PgBouncerDataProvider` implements `DataProvider`
      in `io.cafeai.data.pgbouncer`
- [ ] `DataSource.pgBouncer(config)` returns a `PgBouncerDataProvider`
- [ ] Primary connection pool routes to pgBouncer's transaction-mode port
- [ ] Replica connection pool routes to pgBouncer's read-only port
      when `replicaHost` is configured in `DataSourceConfig`
- [ ] pgBouncer connection pool settings respected:
      `maxPoolSize` maps to pgBouncer's `max_client_conn`
- [ ] `./gradlew :cafeai-data:compileJava` — zero errors

### Notes
pgBouncer integration is the production path. `DataSource.direct()` is
the local dev path. The `DbHandle` API is identical in both cases —
the developer does not know or care which is active.

---

## Phase 12 — Integration Tests (Testcontainers)

**Status:** 🔴 Not Started

### Acceptance Criteria
- [ ] Testcontainers `postgres:16` container used for all integration tests
- [ ] Full lifecycle test: migrations applied → insert → findById →
      query → update → findById (verify update) → delete → findById (verify gone)
- [ ] Eventual consistency test: callback invoked with correct data
- [ ] Migration idempotency test: migrations applied twice, second run skips all
- [ ] Migration checksum violation test: modified migration file detected
- [ ] `DataAccessException` test: malformed SQL produces wrapped exception
- [ ] Connection pool closed cleanly after all tests
- [ ] `./gradlew :cafeai-data:test` — all integration tests pass
- [ ] `./gradlew clean build` — BUILD SUCCESSFUL, zero warnings

### Notes
Testcontainers requires Docker. CI must have Docker available.
pgBouncer integration tests use a `pgbouncer` container alongside
the `postgres:16` container.

---

## Completion Definition

MILESTONE-18 is **complete** when:

1. All 12 phases show ✅ Complete
2. `./gradlew clean build` — BUILD SUCCESSFUL, zero warnings
3. `./gradlew javadoc` — zero warnings on `cafeai-data`
4. `./gradlew :cafeai-data:test` — all unit and integration tests pass
5. `app.db()` is available in a handler, a job handler, and a filter
6. Both `DataSource.direct()` and `DataSource.pgBouncer()` work end-to-end
7. Schema migrations applied automatically on `app.listen()`

**What success looks like:**

```java
var app = CafeAI.create();

app.ai("tutor", OpenAI.gpt4o());
app.db(DataSource.pgBouncer(config));
app.guard(GuardRail.pii());
app.observe(ObserveStrategy.otel());

app.get("/claims/:id", (req, res, next) -> {
    // Strong consistency — primary, synchronous
    var claim = app.db().findById("claims", req.params("id"), Claim.class);
    res.json(claim);
});

app.get("/claims/summary/:id", (req, res, next) -> {
    // Eventual consistency — replica, callback
    app.db().findById("claims", req.params("id"), Claim.class, claim -> {
        var summary = app.prompt("Summarise this claim: " + claim)
            .provider("tutor").call().text();
        res.json(Map.of("summary", summary));
    });
});

app.listen(8080);
// → migrations applied from db/migrations/
// → server listening on :8080
```
