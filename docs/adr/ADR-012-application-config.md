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

A new optional module, `cafeai-config`, following the same shape already
proven in this codebase (`MemoryStrategy`, `RagProvider`, `Connection`): a
contract lives in `cafeai-core`; a heavier implementation is a separate
module, reached through a provider SPI, present only if an application
chooses to add it.

**The split is drawn by role, not by convenience — and it's narrower than it
first looked.** The initial design put a "zero-dependency" resolution tier
directly in `cafeai-core` (`AppConfig.ambient()`: check a system property,
then an environment variable, using a hand-rolled dots→underscores→uppercase
name mapping). That was wrong, for two independent reasons surfaced in
review:

1. It conflated *where a type has to live* with *what role it has to play*.
   `ConfigKey`/`AppConfig`/`ConfigProvider` have to live in `cafeai-core`
   because every module that wants to declare a configurable value already
   depends on `cafeai-core` and must not need a new dependency to do so — if
   the types lived in `cafeai-config` instead, `cafeai-core` itself (which
   needs to declare `LangchainBridge`'s own keys) would need to depend on
   `cafeai-config`, which already depends on `cafeai-core` — an actual
   circular project dependency Gradle would refuse to build. That argument
   proves where the *interfaces* sit. It proves nothing about who is allowed
   to *implement* them, and treating it as if it did was a category error.
2. The hand-rolled name mapping was genuinely fragile, not just
   inelegant — it replaced `.` with `_` and nothing else, so a key
   containing a hyphen or any other separator produced an illegal
   environment-variable name. Helidon/MicroProfile Config's actual mapping
   handles this correctly; ours didn't, because it was a smaller
   reimplementation of the exact thing bringing in Helidon Config was
   supposed to make unnecessary.

**The actual line: `cafeai-core` resolves only a `ConfigKey`'s own coded
default. Every other source — system property, environment variable, file,
whatever a provider composes — is `cafeai-config`'s job, in full.** A key is
a single dotted name, Spring/Helidon style (`cafeai.rag.chunk.size`) — there
is no second, CafeAI-specific spelling for any layer, anywhere. Whether, and
how, that name maps onto an environment variable is entirely Helidon
Config's own established mapping, not a convention this framework invents or
owns. `AppConfig.load()` either fully delegates to a discovered
`ConfigProvider`, or — `cafeai-config` absent — returns the coded default
unconditionally: exactly the behavior every one of these values already had
before it was a `ConfigKey`. Declaring a value as a `ConfigKey` is what gives
it a real path to being overridden; it never risks a working default
disappearing.

**In `cafeai-core` (`io.cafeai.core.config`):**

- `ConfigKey<T>` — a self-documenting key: name, type, default, one-line
  description. Declared *locally*, at the point of use (e.g. directly in
  `LangchainBridge`, replacing the old bare constant), not in a central
  catalog file — so the documentation cannot drift out of sync with what's
  actually read, the same reasoning as ADR-011's package-level rationale.
  Constructing a key self-registers it into `ConfigCatalog`, mirroring how
  `CafeAIModule` self-registers via `ServiceLoader` — declaring it *is*
  registering it.
- `AppConfig` — resolves a `ConfigKey` to its value: `get(key)` returns
  whatever `getRaw(key)` resolves, or the key's own default. No default
  implementation ships in `cafeai-core` beyond that fallback.
- `io.cafeai.core.spi.ConfigProvider` — the SPI. Its implementation owns
  *all* resolution; `AppConfig.load()` checks nothing itself before
  consulting it.

**`cafeai-config` supplies:** `HelidonConfigProvider`, built entirely on
Helidon Config (`helidon-config` + `helidon-config-yaml`) — the same "don't
reinvent, build on Helidon" choice `cafeai-core` already makes for its HTTP
layer (ADR-001) — rather than a hand-rolled merger. Those two dependencies
live entirely inside `cafeai-config`; no other module ever sees
`io.helidon.config` directly. (They had, in fact, already been added to
`cafeai-core` in anticipation of this feature and then never used — found
and removed as dead weight from every application's baseline while building
this.)

Sources, highest precedence first:

1. system properties
2. environment variables
3. an external file, if `CAFEAI_CONFIG_FILE` (env var) or `cafeai.config.file`
   (system property) points to one — configuration mounted outside the jar,
   e.g. a Kubernetes ConfigMap volume
4. `application-{profile}.yaml`/`.yml`/`.properties` on the classpath, when a
   profile is active (`CAFEAI_PROFILE` / `cafeai.profile`)
5. `application.yaml`/`.yml`/`.properties` on the classpath

Every file-based source is optional — a missing one is silently skipped, not
an error, since an application may configure entirely through system
properties and environment variables and ship no file at all.

**No module that reads configuration depends on `cafeai-config`.**
`ConfigKey` and `AppConfig` live in `cafeai-core`, which every module already
depends on. Whether real resolution is active at all is entirely a decision
the *application author* makes by adding `cafeai-config` to their own app —
a library module (`cafeai-rag`, `cafeai-agents`, `cafeai-sentinel`) never
checks for it and never needs a new dependency to declare a key.

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
- Without `cafeai-config` on the classpath, every `ConfigKey` resolves to
  its coded default, unconditionally — there is no partial, zero-dependency
  override tier. That's a deliberate simplification, not an oversight: the
  only cost of *not* adding `cafeai-config` is "you get the default," which
  is exactly what every one of these values already did before this system
  existed.
- `ConfigCatalog.known()` is only complete once every class that declares a
  key has been loaded by the JVM. Read it after the application has been
  running a while — log it at the end of `app.listen()`, or expose it on a
  debug route — not on the first line of `main()`.
- Internal implementation limits that bound memory or prompt size for
  correctness (`cafeai-sentinel`'s `MAX_EVENTS_PER_POD`, `MAX_EVIDENCE`, and
  similar) are not automatically `ConfigKey` candidates — the test is
  whether a real deployment would plausibly want to change it, not whether
  it's a constant at all.
