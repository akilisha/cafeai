# cafeai-config — Three Hardcoded Constants Nobody Could Change

*Post 14 of 14 in the CafeAI series*

---

An audit of the framework — done for an unrelated reason, comparing
`cafeai-rag` against a plain LangChain4j tutorial (post 6's territory) —
turned up something worse than complexity. `LangchainBridge`, the class every
single `app.prompt()` / `app.vision()` / `app.audio()` call eventually goes
through, hardcoded a 60-second timeout into every LLM call, any provider,
at six separate call sites. No setter. No environment variable. Nothing
written down anywhere that it existed. Two more constants of the identical
shape turned up in the same pass: `AgentRegistry`'s chat-memory window, fixed
at 20 messages regardless of which `MemoryStrategy` an application had
configured, and a webhook retry count buried in `cafeai-sentinel`'s
`WebhookSink` — the same module post 13 covered.

None of these were edge cases. They were live in every application built on
the framework, silently, since before this series started.

## The comparison, and what it wasn't asking for

Spring Boot's `application.properties` + `Environment` got raised as the
counter-example, and it's worth being precise about what that comparison was
and wasn't. It wasn't "go build that" — an external file bound to objects by
reflection is exactly the hidden wiring this series has spent twelve posts
arguing against (post 2's middleware thesis, post 8's guardrails boundary).
It was raised as evidence of something narrower: a framework serious enough
to be used in production treats "how does someone tune this for their
environment" as a first-class concern, with real machinery behind it, not
three unrelated magic numbers nobody wrote down.

## The design that shipped first, and was wrong

The first attempt put a whole resolution tier directly in `cafeai-core`:
`AppConfig.ambient()`, checking a system property, then an environment
variable, using a hand-rolled mapping from a dotted key like
`cafeai.rag.chunk.size` to an environment variable name
(`CAFEAI_RAG_CHUNK_SIZE`) by replacing `.` with `_` and upper-casing.

It didn't survive review. Two separate problems broke it:

**It confused where a type has to live with what role it gets to play.**
`ConfigKey`, `AppConfig`, and the `ConfigProvider` SPI genuinely have to sit
in `cafeai-core` — every module that wants to declare a tunable value already
depends on `cafeai-core`, and none of them can be made to depend on a new
`cafeai-config` module just to declare one, or the dependency graph would
have to run in a circle. That's a real, narrow constraint, and it settles
where the *interfaces* live. It settles nothing about who is allowed to
*resolve* them — treating it as though it did was the actual mistake.

**The hand-rolled mapping was fragile, not just inelegant.** It replaced `.`
with `_` and nothing else. A key containing a hyphen would have produced an
illegal environment variable name — not a hypothetical edge case, just a
bug waiting for the first key that needed one. Helidon Config's own mapping
already handles this correctly. The reimplementation existed purely to avoid
a dependency the framework was already planning to add.

## The line that actually holds

The corrected design, exactly as it settled: **`cafeai-core` resolves only a
`ConfigKey`'s own coded default. Every other source is `cafeai-config`'s job,
in full.**

```java
public static final ConfigKey<Duration> CHAT_TIMEOUT = ConfigKey.of(
        "cafeai.chat.timeout", Duration.class, Duration.ofSeconds(60),
        "Timeout for a single LLM chat call, any provider");

private static Duration timeout() {
    return AppConfig.load().get(CHAT_TIMEOUT);
}
```

That's the actual fix committed into `LangchainBridge` — a `ConfigKey`
declared as a `public static final` field, right next to the six call sites
that use it, replacing a bare `private static final Duration` constant. The
same shape landed in `AgentRegistry` (`cafeai.agent.memory.window`, default
20) and `WebhookSink` (`cafeai.sentinel.webhook.timeout` /
`.max_attempts`). Constructing a `ConfigKey` self-registers it into
`ConfigCatalog` — "declaring it is registering it," the same idiom
`CafeAIModule` already uses for module discovery, now proven a third time
after `MemoryStrategy` and RAG's provider split.

`AppConfig.load()` is intentionally small:

```java
static AppConfig load() {
    return ServiceLoader.load(ConfigProvider.class)
        .findFirst()
        .<AppConfig>map(ConfigProvider::config)
        .orElse(key -> Optional.empty());
}
```

No `cafeai-config` on the classpath, no `ConfigProvider` found, `get()` falls
straight through to `key.defaultValue()`. That's not a degraded mode — it's
the exact behavior every one of these three values already had before this
system existed. Declaring a value as a `ConfigKey` only ever adds a path to
overriding it; it can't make a working default disappear.

## What cafeai-config actually resolves

`cafeai-config` supplies `HelidonConfigProvider` — built entirely on Helidon
Config, not a second hand-rolled merger, the same "don't reinvent, build on
Helidon" choice `cafeai-core` already made for its HTTP layer. Five sources,
highest precedence first: system properties, environment variables, an
external file if `CAFEAI_CONFIG_FILE` or `cafeai.config.file` points to one
(a mounted Kubernetes ConfigMap, say), `application-{profile}.yaml` when a
profile is active, then plain `application.yaml`. Every file source is
optional and silently skipped if absent — an application can configure
entirely through environment variables and ship no file at all.

```yaml
# application.yaml
cafeai:
  chat:
    timeout: 90s
  agent:
    memory:
      window: 40
```

Dotted names throughout, the same spelling in the key, the file, and (via
Helidon's own established mapping, not a CafeAI one) the environment
variable form. No module that merely declares a `ConfigKey` needs
`cafeai-config` as a dependency — whether real resolution is active at all
is a decision the *application* makes once, by adding one jar.

## The boundary that stayed firm

Config supplies values; it never wires capabilities. A `ConfigKey` can say
what a timeout is. It can never cause `app.ai(...)` or `app.vectordb(...)`
to fire on its own — the moment a config file starts constructing objects,
this stops being "documented tuning values" and becomes the exact
auto-configuration this series has refused since post 2. `application.yaml`
in CafeAI is closer to a `.env` file than to a Spring context definition:
it answers questions the code explicitly asks, by key, and does nothing on
its own initiative.

## What this proves, honestly

Three previously invisible constants are now real, documented,
overridable `ConfigKey`s — one of them in the exact module post 13 spent an
entire post validating on a real cluster, which had shipped with an
undocumented, unconfigurable webhook retry count the whole time. The
"contract in core, implementation behind a provider SPI" shape, proven
first by `MemoryStrategy` and then by RAG's own provider split, generalizes
to something that isn't a capability at all — just a place to get answers
from.

The honest gap: the wrong design shipped first, inside this repository, and
needed a second pass to correct — not caught by getting the architecture
right up front. That's worth stating plainly rather than editing out of the
history the way a changelog alone would.

## Closing

No capstone exercises `cafeai-config` yet; all five predate it. The point of
this post isn't a new application — it's three bugs that would never show up
in any of them, because none of the three ever threw, crashed, or failed a
test. They just quietly did the same wrong thing forever, for every user of
the framework, until someone went looking. Full design record, including the
rejected first attempt in its own words: `docs/adr/ADR-012-application-config.md`.

---

*CafeAI: Not an invention of anything new. A re-orientation of everything proven.*
