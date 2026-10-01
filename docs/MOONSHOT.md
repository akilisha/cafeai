# Moonshots

Ideas big enough to get CafeAI noticed. Each one has to pass one test: can it be
shown in a single blog post, a single tweet, or ten seconds of someone's terminal?
Ordinary features make people stay; these are meant to make people look.

| # | Moonshot | Status |
|---|----------|--------|
| 1 | [The ten-second demo (JBang)](#1-the-ten-second-demo-jbang) | **Done** |
| 2 | [Benchmarks that settle the argument](#2-benchmarks-that-settle-the-argument) | Pilot done |
| 3 | [Java that starts like Go](#3-java-that-starts-like-go) | Idea |
| 4 | [Record and replay for LLM calls](#4-record-and-replay-for-llm-calls) | Idea |
| 6 | [Every app is an MCP server](#6-every-app-is-an-mcp-server) | Idea |
| 11 | [Express middleware on virtual threads](#11-express-middleware-on-virtual-threads) | Idea |

(The numbers are from the list these were picked from, kept so they stay stable.)

---

## 1. The ten-second demo (JBang)

**The pitch:** `jbang hello@akilisha/cafeai` and you have a running server. No
clone, no Gradle, no project.

Express spread on a five-line hello world you could paste and run. Java's first
impression has always been a build file. JBang removes it: a script declares its
own dependencies (`//DEPS`) and Java version (`//JAVA`), and a
`jbang-catalog.json` at the repo root makes every script runnable by name,
straight from GitHub.

**What exists:**

- `jbang/hello.java` — routes and JSON, no API key. Also the template behind
  `jbang init -t cafeai@akilisha/cafeai app.java` (its class is package-private so
  it compiles under any file name).
- `jbang/ask.java` — `POST /ask` backed by Anthropic, OpenAI, or a local Ollama,
  whichever the environment offers.
- `jbang-catalog.json` — the `hello` and `ask` aliases and the `cafeai` template.
- The README opens with all three commands.

**The rule that keeps it working:** the catalog serves the scripts from `main`, so
their `//DEPS` lines may only name a version Maven Central already serves. Bump
them after a release, never before (step 6 in `distribution.md`).

---

## 2. Benchmarks that settle the argument

**The pitch:** *plain blocking code, reactive-level numbers* — with a chart.

The strongest thing CafeAI has, AI aside, is Helidon 4's virtual-thread server:
handlers call JDBC or sleep or block on HTTP, and it still scales, without the
callback chains of WebFlux or Vert.x. That claim is worth nothing as an
adjective and a great deal as a graph.

**Shape of it:** the same small app on each contender, the same hardware, the
same load generator, published with the scripts to reproduce it.

- *Contenders:* CafeAI; Spring WebFlux (the reactive answer); Spring MVC on
  virtual threads (the incumbent's own answer); Javalin (the closest rival);
  Express (the mental model CafeAI borrows).
- *Workloads:* a plain JSON response (raw overhead); a handler that blocks on I/O
  (the virtual-thread case, where the claim lives or dies); a database query.
- *Measures:* throughput, p50/p99/p99.9 latency, memory, under rising concurrency.

**Risk:** the numbers might be ordinary. Better to learn that before marketing
anything else. A TechEmpower Framework Benchmarks entry would make the result
independent of us.

### Pilot (2026-10-01) — not publishable, but encouraging

CafeAI 0.5.0 from Maven Central, run with JBang on Java 28-ea; k6 on the same
laptop (i7-11800H, 8 cores / 16 threads, Windows 11). The load generator shared
the CPU with the server, so these show behaviour, not capacity.

| Workload | Requests/s | p50 | p99 | Failures |
|---|---|---|---|---|
| `GET /json`, 100 connections | 55,300 | 1.0 ms | 6.1 ms | 0 |
| handler blocks 100 ms, ramp to 4,000 connections | 26,700 | 104 ms | 247 ms | 0 |
| handler blocks 100 ms, ramp to 8,000 connections | 28,900 | 133 ms | 414 ms | 0 |

With 4,000 requests each blocked for 100 ms, the median is 104 ms — requests
almost never queued for a thread. A platform-thread server would need 4,000 OS
threads for that.

**What the pilot taught about method:**

- Opening thousands of connections in the same instant overflows the server's
  accept queue ("connection refused"), and a refused client retries in a tight
  loop, so failures look far worse than they are (39% in the first run). Ramp
  the load. The queue size is configurable: `app.helidon().server(b -> b.backlog(n))`.
- Git Bash rewrites arguments that look like paths (`/json` became a Windows
  path). Set `MSYS_NO_PATHCONV=1` when driving tools from it.
- One machine cannot tell whether the server or the load generator gave out first.

### Plan for publishable numbers

1. **Two machines, Linux.** Server and load generator on separate, identical
   cloud VMs in one zone (not this laptop, not Windows — readers rightly
   distrust both). Fixed CPU counts, results reported per core.
2. **Same rules for everyone.** One JDK for all JVM contenders; each contender
   written idiomatically and tuned the way its own docs recommend; warm-up
   before measuring; several runs, median reported.
3. **Workloads:** plain JSON; handler blocking 10 / 100 ms (the virtual-thread
   case); a real PostgreSQL query (pool size is part of the story).
4. **Measures:** throughput and p50/p99/p99.9 at rising concurrency — the curve
   matters more than any single number — plus memory.
5. **Everything public:** apps, load scripts, raw results, machine specs, and an
   invitation for each framework's maintainers to correct their entry.

---

## 3. Java that starts like Go

**The pitch:** starts in tens of milliseconds, runs in tens of megabytes — so
serverless and scale-to-zero stop being places Java loses.

Two routes: a GraalVM native image, or Java 25's ahead-of-time cache (Project
Leyden), which keeps the normal JVM and needs no reflection configuration.
`hello.java` already reports ~0.8 s from JVM start to serving on a plain JVM;
that is the baseline to beat.

**Risk:** LangChain4j leans on reflection and dynamic proxies, which native image
must be told about. Unknown how much survives. The AOT cache sidesteps most of it
and is the cheaper first experiment.

---

## 4. Record and replay for LLM calls

**The pitch:** test your AI app in CI with no API key, no cost, and the same answer
every run.

In development, model calls are recorded to files ("cassettes"); in tests they
are replayed. Ruby's VCR did this for HTTP. AI apps need it more: real model calls
are slow, cost money, change between runs, and need secrets CI shouldn't hold —
CafeAI's own cloud live tests sit blocked on keys and free-tier quotas today.

**Shape of it:** a provider wrapper — `Replay.of(provider, cassetteDir)` — keyed on
the request (model, messages, parameters). Modes: record, replay, replay-or-fail.
A missing cassette fails loudly rather than quietly calling the real model.

**Pairs with:** evals as tests. Model ids go stale, so models get swapped, and a
swap is exactly when you want to know what changed.

---

## 6. Every app is an MCP server

**The pitch:** one line, and the routes you choose become tools any AI agent can
call — with your guardrails, sessions, and observability already on them.

Serving MCP already goes through Helidon's `McpFeature` (no module). The moonshot
is the one-liner — `app.mcp()` — that derives tool definitions from routes the app
opts in, so an existing CafeAI API is agent-ready without a second codebase.

**Open question:** how a route describes its inputs well enough for a model to
call it (a schema per route, or the request type's record components).

---

## 11. Express middleware on virtual threads

**The pitch:** your existing Express middleware, running inside CafeAI.

GraalJS runs JavaScript on the JVM. Adapt Express's `(req, res, next)` to
CafeAI's — which was modelled on it — and npm middleware could run in a Java
server, on virtual threads.

**Risk:** probably impractical in full. Express middleware reaches into Node's
`http` objects and streams, and those semantics differ in many small ways. A
narrow version — pure functions of headers and body, like many auth and
validation middlewares — might be real. Worth a spike to find where it breaks,
because even a partial result is a headline.
