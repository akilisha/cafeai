# ADR-012: Application Configuration — `cafeai-config`

**Status:** Accepted
**Date:** September 2026

## Context

An audit of the framework (2026-09) found real, undocumented, non-overridable
constants sitting in production code paths — most seriously, `LangchainBridge`
hardcoded a 60-second timeout into every LLM chat call, any provider, with no
setter, no environment variable, no system property, and nothing written down
anywhere that it existed. `AgentRegistry`'s chat-memory window (20 messages,
fixed regardless of the `MemoryStrategy` configured) and `WebhookSink`'s retry
count had the identical shape. None of these were edge cases; they were live
in every application built on the framework.

The fair comparison raised at the time was Spring Boot's `application.properties`
+ `Environment` — not as something to copy wholesale (an external properties
file bound by reflection is exactly the kind of hidden wiring this framework
refuses to do everywhere else — see `docs/EXTENDING.md`: "no annotation
scanner, no DI container"), but as evidence that a serious framework treats
"how does a user tune this for their environment" as a first-class concern,
not an afterthought.

## Decision

A new optional module, `cafeai-config`, following the exact pattern already
proven three times in this codebase (`MemoryStrategy`, `RagProvider`,
`Connection`): a minimal, zero-dependency contract lives in `cafeai-core`;
the heavier implementation is a separate module, reached through a provider
SPI, present only if an application chooses to add it.

**In `cafeai-core` (`io.cafeai.core.config`):**

- `ConfigKey<T>` — a self-documenting key: name, type, default, one-line
  description. Declared *locally*, at the point of use (e.g. directly in
  `LangchainBridge`, replacing the old bare constant), not in a central
  catalog file — so the documentation cannot drift out of sync with what's
  actually read, the same reasoning as ADR-011's package-level rationale.
  Constructing a key self-registers it into `ConfigCatalog`, mirroring how
  `CafeAIModule` self-registers via `ServiceLoader` — declaring it *is*
  registering it.
- `AppConfig` — resolves a `ConfigKey` to its value. `AppConfig.ambient()` is
  the zero-dependency Rung 1: system properties, then environment variables,
  no files. `AppConfig.load()` checks `ambient()` first and only falls
  through to `cafeai-config`'s file layer via `ConfigProvider` when both are
  absent — so a value's origin is checked cheapest-first, and a library
  module calling `AppConfig.load()` never needs to know or branch on whether
  `cafeai-config` is even present.
- `io.cafeai.core.spi.ConfigProvider` — the SPI. Its single implementation
  only needs to worry about files; `AppConfig.load()` has already checked
  system properties and environment variables before ever reaching it.

**`cafeai-config` supplies:** `PropertiesConfigProvider` — loads
`application.properties` from the classpath, overlaid by
`application-{profile}.properties` when a profile is active (`CAFEAI_PROFILE`
env var, or `cafeai.profile` system property, which wins if both are set).
A missing file of either kind is silently skipped, not an error — an
application may configure entirely through environment variables and ship no
file at all.

**Precedence, highest to lowest:**

```
system property → environment variable → application-{profile}.properties
                → application.properties → the ConfigKey's own coded default
```

**Naming convention:** one logical key, two spellings. `cafeai.rag.chunk.size`
(properties file, system property) and `CAFEAI_RAG_CHUNK_SIZE` (environment
variable) name the same key — dots become underscores, uppercased. A user
overriding a value only ever needs to remember the key once, regardless of
which layer they set it in.

**No module that reads configuration depends on `cafeai-config`.**
`ConfigKey` and `AppConfig` live in `cafeai-core`, which every module already
depends on. Whether the richer file/profile loading is active is entirely a
decision the *application author* makes by adding `cafeai-config` to their
own app — a library module (`cafeai-rag`, `cafeai-agents`, `cafeai-sentinel`)
never checks for it and never needs a new dependency to participate.

**Boundary, deliberate and firm: config supplies values, never wires
capabilities.** A key can say what a timeout is; it can never cause
`app.ai(...)`, `app.vectordb(...)`, or any other registration call to happen
on its own. The moment a config file starts constructing objects or
registering capabilities by itself, this stops being "documented tuning
values" and becomes Spring Boot auto-configuration — the specific kind of
hidden wiring the rest of this framework has refused everywhere else.
Application code stays the only thing that calls `app.*` methods; config
only ever answers a question that was explicitly asked, by key.

## Consequences

- Any hardcoded constant that a real deployment might reasonably need to
  tune — a timeout, a pool size, a retry count, a window size — should be a
  `ConfigKey`, declared where it's used, not a bare `private static final`.
  `LangchainBridge.CHAT_TIMEOUT`, `AgentRegistry.MEMORY_WINDOW`, and
  `WebhookSink.TIMEOUT`/`MAX_ATTEMPTS` are the first three; they were also
  the three concrete bugs that motivated this ADR.
- `ConfigCatalog.known()` is only complete once every class that declares a
  key has been loaded by the JVM. Read it after the application has been
  running a while — log it at the end of `app.listen()`, or expose it on a
  debug route — not on the first line of `main()`.
- Internal implementation limits that bound memory or prompt size for
  correctness (`cafeai-sentinel`'s `MAX_EVENTS_PER_POD`, `MAX_EVIDENCE`, and
  similar) are not automatically `ConfigKey` candidates — the test is
  whether a real deployment would plausibly want to change it, not whether
  it's a constant at all.
