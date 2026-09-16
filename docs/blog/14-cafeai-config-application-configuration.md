<!-- OUTLINE DRAFT — structure only, for review. Not for publishing as-is. -->

# cafeai-config — The Configuration Nobody Documented

*Post 14 of 14 in the CafeAI series*

---

## Opening — the arrogance of an undocumented default

- Narrative hook: an audit of the framework, done for an entirely different
  reason (reviewing `cafeai-rag` against a plain LangChain4j tutorial —
  post 6's territory), turned up something worse than complexity: a
  60-second chat timeout hardcoded into every LLM call, any provider, with
  no setter, no environment variable, and nothing written down anywhere
  that it existed. Two more of the same shape followed immediately —
  `AgentRegistry`'s 20-message memory window, `WebhookSink`'s retry count.
- The framing that matters: this is not an edge case. These three values
  were live in every application built on the framework, silently, since
  the beginning. A framework that hides its own tuning knobs from the
  people running it in production is making a decision on their behalf
  without telling them it made one.
- The comparison that was raised, and the pushback on it worth including
  verbatim in spirit: Spring Boot's `application.properties` + `Environment`
  wasn't proposed as something to copy wholesale — an external file bound
  by reflection is exactly the kind of hidden wiring this framework
  refuses everywhere else (post 2's middleware thesis, post 8's guardrails
  boundary). It was raised as evidence that a serious framework treats
  "how does a user tune this for their environment" as a first-class
  concern, not an afterthought. That distinction is the spine of this post.

## The design that felt right and wasn't

- First attempt: `AppConfig.ambient()`, living entirely in `cafeai-core` —
  check a system property, then an environment variable, using a
  hand-rolled `cafeai.rag.chunk.size` → `CAFEAI_RAG_CHUNK_SIZE` name
  mapping (dots to underscores, uppercase).
- It survived one round of review before a sharper question broke it:
  *what is the actual value proposition of splitting lookup between two
  modules at all?* The honest first answer — "some resolution is
  zero-dependency, so it belongs in core" — collapsed under a harder
  challenge: that conflates *where a type has to live* (a real, narrow
  constraint — every module already depends on `cafeai-core`, so a
  circular Gradle dependency rules out putting `ConfigKey`/`AppConfig`
  anywhere else) with *what role that type gets to play*. Nothing about
  the dependency graph says `cafeai-core` itself has to be the one
  resolving system properties.
- The second, concrete crack: the hand-rolled mapping was fragile, not
  just inelegant. It replaced `.` with `_` and nothing else — a key
  containing a hyphen produced an illegal environment variable name.
  Helidon/MicroProfile Config's real mapping already handles this
  correctly. The reimplementation existed only to avoid a dependency that
  the framework was about to bring in anyway.

## The line that actually holds

- The corrected rule, stated exactly as it was settled on: **`cafeai-core`
  resolves only a `ConfigKey`'s own coded default. Every other source —
  system property, environment variable, file, whatever a provider
  composes — is `cafeai-config`'s job, in full.**
- `ConfigKey<T>` — name, type, default, one-line description, declared
  *locally* at the point of use, not in a central catalog file (the same
  reasoning as post 6's chunking/embedding boundary in `cafeai-rag`: the
  documentation cannot drift from what's actually read if there is no
  second copy of it to drift from). Constructing one self-registers it
  into `ConfigCatalog` — "declaring it is registering it," the same
  pattern `CafeAIModule` already uses, now proven a third time.
- `cafeai-config` supplies `HelidonConfigProvider` — built entirely on
  Helidon Config, the same "don't reinvent, build on Helidon" choice the
  HTTP layer already made (ADR-001). Five-tier precedence: system
  property → environment variable → an external file (`CAFEAI_CONFIG_FILE`,
  e.g. a mounted Kubernetes ConfigMap) → `application-{profile}.yaml` →
  `application.yaml`. Dotted names throughout — `cafeai.rag.chunk.size` —
  no CafeAI-invented spelling at any layer.
- Worth landing explicitly: no module that merely *declares* a key needs
  `cafeai-config` as a dependency. Whether real resolution is active at
  all is an *application* decision, made once, by adding one jar.

## The boundary that was almost crossed

- The firm, deliberate line: config supplies values, it never wires
  capabilities. A key can say what a timeout is; it can never cause
  `app.ai(...)` or `app.vectordb(...)` to fire on its own. The moment a
  config file starts constructing objects, this stops being "documented
  tuning values" and becomes Spring Boot auto-configuration — the exact
  hidden wiring the rest of the framework has refused since post 2.
- Naming this boundary out loud matters for the same reason the guardrails
  post (8) named its own: a framework earns trust by being precise about
  what it will never do automatically, not just what it can do.

## What this proves

- Three previously invisible constants are now real, overridable,
  documented `ConfigKey`s: `cafeai.chat.timeout`, `cafeai.agent.memory.window`,
  `cafeai.sentinel.webhook.timeout` / `.max_attempts` — the last one a
  direct line back to post 13's pipeline.
- The "contract in core, implementation in a separate module, reached
  through a provider SPI" shape — proven first by `MemoryStrategy`
  (post 5), then by RAG's own provider split — generalizes cleanly to a
  concern that isn't a capability at all, just a place to get answers
  from. That's a stronger claim about the shape than either prior
  instance made alone.
- Honest counter-note, matching the series' credibility pattern (posts 12
  and 13 both do this): the wrong design shipped first, in-repo, and had
  to be corrected after review — not caught in advance by getting the
  architecture right the first time. Worth naming plainly.

## Closing

- No capstone exercises `cafeai-config` yet — the four built ones predate
  it, and retrofitting one isn't the point of this post. The point is
  narrower and, arguably, more useful: three real bugs of *omission* that
  a fifth capstone would never have found, because nothing about them
  throws, crashes, or fails a test. They just quietly do the wrong thing
  forever, for every user, until someone reads the source.
- Pointers for anyone who wants the full design detail:
  `docs/adr/ADR-012-application-config.md` (including the corrected design
  with its own record of the rejected first attempt) and
  DEVELOPER_GUIDE.md §17.
- Standard closing tagline.
