# Moonshots

Ideas big enough to get CafeAI noticed. Each one has to pass one test: can it be
shown in a single blog post, a single tweet, or ten seconds of someone's terminal?
Ordinary features make people stay; these are meant to make people look.

| # | Moonshot | Status |
|---|----------|--------|
| 1 | [The ten-second demo (JBang)](#1-the-ten-second-demo-jbang) | **Done** |
| 2 | [Benchmarks that settle the argument](#2-benchmarks-that-settle-the-argument) | Round 1 done (vs Spring MVC) |
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

### Round 1 on DigitalOcean (2026-10-01)

Everything needed to rerun this is in [`bench/`](../bench): the apps
(`bench/apps`), the droplet boot scripts and app control script
(`bench/droplets`), the load suite and CPU analysis (`bench/load`), and every raw
result (`bench/results/2026-10-01-do-c4`).

#### The machines

Two **CPU-Optimized** droplets (`c-4`: 4 dedicated vCPU, 8 GB, $0.125/hour each)
in `nyc1`, Ubuntu 24.04, talking over the private network. CPU-Optimized, not
Basic: Basic droplets share CPU with other customers, which turns into noise.

```bash
doctl auth init                                   # once: paste an API token with write scope
for n in server loadgen; do
  doctl compute droplet create cafeai-bench-$n --region nyc1 --size c-4 \
    --image ubuntu-24-04-x64 --ssh-keys <key-id> --tag-name cafeai-bench \
    --user-data-file bench/droplets/$n-init.sh --enable-monitoring --wait
done
# ...and afterwards, so the meter stops:
doctl compute droplet delete --tag-name cafeai-bench --force
```

- Both boot scripts raise kernel network limits (accept queue, open files,
  client port range). The server gets Temurin 25 and JBang; the load generator
  gets `wrk2` (built from source) and k6. `/var/log/bench-ready` appears when done
  (about five minutes).
- The server's firewall (`ufw`) allows port 8080 only from the private network.
- `bench/droplets/app.sh start <app> [JVM options]` / `stop` runs an app and tracks
  its pid; `bench/load/suite.sh <name> <host:port> <out-dir>` runs the suite from
  the load generator; `mpstat 1` runs on the server alongside it.

**What went wrong along the way (all fixed in the scripts):**

- `c-4` is not offered in every region (`nyc3` refused it). Check with
  `doctl compute region list -o json` and look for the size in each region's list.
  This account's CPU-Optimized sizes stop at 4 vCPU, so the load generator cannot
  be bigger than the server — which is why load-generator CPU is checked on every run.
- cloud-init runs without `HOME`, so JBang installed itself under `/.jbang`.
- k6's apt repository changed its signing key and the image has no `dirmngr` to
  fetch keys; the script installs k6's release binary instead.
- `pkill -f "java.*cafeai"` killed the ssh session running it — its own command
  line matched. Hence `app.sh` and a pid file.
- `wrk2` spends ~10 s calibrating; runs shorter than that are meaningless. The
  suite uses 30 s runs and the CPU analysis skips each run's first 12 s.

#### The first finding: CafeAI leaked every request

The first run on the server died with `OutOfMemoryError` during warm-up, at
20,000 requests/s. The laptop pilot never noticed, because a 64 GB machine gives
the JVM a ~16 GB heap; the 8 GB droplet gives it ~2 GB.

`jstat` showed the old generation growing ~50 MB/s and never shrinking — about
2.6 KB kept per request. A class histogram showed exactly 761,588 live
`HelidonRequest` and `HelidonResponse` objects and as many `WeakHashMap` entries:
every request ever served.

The cause was in `CafeAIApp`: each request's context lived in an app-wide
`WeakHashMap` keyed by Helidon's request, but the value wrapped that same key, so
no key could ever be released — and one global lock guarded the map on every
filter and handler call. The context now lives in the Helidon request's own
`context()`, freed with the request, with no shared lock.
`RequestMemoryTest` fails on the old code and passes on the fix.

| Same load, 40 s at ~19,900 req/s | Old generation | p99 latency |
|---|---|---|
| 0.5.0 | 1,864 MB and climbing, then out of memory | 1,560 ms |
| 0.5.0 + fix | 9 MB, flat | 2.1 ms |

All CafeAI numbers below are 0.5.0 with this fix.

#### Results: CafeAI vs Spring MVC on virtual threads

Same JDK (Temurin 25.0.4), same default heap, both confirmed running handlers on
virtual threads (`GET /thread`). Spring Boot 4.1.1 is shown with its defaults and
tuned: `max-keep-alive-requests=-1`, `max-connections=20000`, `accept-count=10000`.
Every run is 30 s at a fixed request rate; no run had a connect error or a non-2xx
response.

**Raw overhead — `GET /json`, 256 connections:**

| | Ceiling (req/s) | p99 at 40,000 req/s | Server CPU at 20,000 req/s |
|---|---|---|---|
| **CafeAI** | **~99,000** | **2.7 ms** | **19.7%** |
| Spring MVC | ~50,000 | 4.3 ms | 51.5% |
| Spring MVC, tuned | ~53,000 | 5.0 ms | 44.4% |

**The virtual-thread case — every request blocks 100 ms** (requested: 90% of what
the connections could carry):

| Connections | CafeAI | Spring MVC | Spring MVC, tuned |
|---|---|---|---|
| 1,000 | 8,672/s, p99 106 ms | 8,671/s, p99 989 ms | 8,671/s, p99 131 ms |
| 2,000 | 17,094/s, p99 110 ms | 17,065/s, p99 1,170 ms | 17,094/s, p99 193 ms |
| 4,000 | **32,797/s, p99 162 ms** | 19,050/s, p99 12.3 s | 20,311/s, p99 11.0 s |
| 8,000 | **41,027/s**, 0 timeouts | 16,663/s, 11,649 timeouts | 17,257/s, 13,869 timeouts |

**Reading it:**

- **Every ceiling is the server's CPU** (97–99%); the load generator never passed
  70%. So these measure the frameworks, not the rig.
- **CafeAI does the same work for less than half the CPU.** That is the whole
  story behind both ceilings: 2.6× less CPU per JSON request at the same rate.
- **Spring's default p99 of ~1 s at 1,000 connections is a setting, not a limit:**
  Tomcat closes keep-alive connections after 100 requests, and the reconnects
  queue. Tuning fixes it (131 ms). Tuning does not move the ceilings, because
  those are CPU.
- **Spring's 8,000-connection errors are timeouts, not refusals** — an overloaded
  CPU answering too slowly, not a full accept queue.
- **A blocking request costs ~2.4× the CPU of a JSON one in CafeAI** (81% for
  32,800/s against 83% for 78,500/s): parking and waking a virtual thread per
  request. Worth profiling; it is the next-cheapest throughput to win.

#### Caveats before any of this is published

- One run per configuration; the plan calls for several and the median.
- 4 vCPU only. The shape may change on bigger machines.
- The fix is unreleased: these CafeAI numbers are not what 0.5.0 on Maven Central
  does (that runs out of memory). Publish only after the fixed release.
- Spring was tuned by us, from its documentation. Its maintainers should get the
  chance to tune it better.
- Still to run: Spring WebFlux, Javalin, Express, and the PostgreSQL workload.

### Round 2: the full field, plus memory (2026-10-01)

Same droplets, same suite, every contender back to back with
`bench/run-all.sh`. CafeAI and both Spring MVC configurations run again, which
also gives them a second run.

**The contenders**, each written the way its own users would write it — so each
waits 100 ms its own idiomatic way:

| App | Version | Runs on | Waits 100 ms with |
|---|---|---|---|
| CafeAI | 0.5.0 + leak fix | Helidon 4.5.5, virtual threads | `Thread.sleep` |
| Spring MVC | Boot 4.1.1 | Tomcat, virtual threads (`spring.threads.virtual.enabled`) | `Thread.sleep` |
| Spring MVC, tuned | Boot 4.1.1 | as above + keep-alive and connection limits raised | `Thread.sleep` |
| Spring WebFlux | Boot 4.1.1 | Netty event loop | `Mono.delay` (sleeping would stall the event loop) |
| Javalin | 7.2.3 | Jetty, virtual threads (`config.concurrency.useVirtualThreads`) | `Thread.sleep` |
| Express | 5.2.1, Node 24.21 LTS | `cluster`, one worker per CPU (4) | `setTimeout` |
| Gin | 1.12.0, Go 1.27.1 | release mode, no logger middleware | `time.Sleep` (one goroutine per request) |

The JVM apps all run on Temurin 25.0.4 with the JVM's default heap sizing (a
quarter of RAM, ~2 GB here) — what a team gets without tuning. Express runs as a
cluster because one Node process uses one core; a single process on a 4-CPU box
would not be a fair Express.

**Memory** is the resident set size (RSS) of everything the app runs — for
Express, the primary and all four workers — sampled every second
(`app.sh memwatch`), averaged and peaked over each 30-second run after wrk2's
calibration. For the JVMs, RSS includes heap the JVM has reserved and touched,
not only live objects: a JVM given 2 GB will use a good part of it, and the
number reflects that default as much as the framework.

**What went wrong, and why `app.sh` is stricter now:**

- The first smoke test of the new apps reported every one of them healthy —
  because the tuned Spring server from round 1 had never been stopped and
  answered every request. Leftover Gin and Express processes from that same smoke
  test then held the port too: the new Gin could not bind and exited, and new
  Express workers died, while the old ones kept answering.
- Fix: each app now runs in its own session (`setsid`); stop kills the whole
  session and then anything still on port 8080; **start refuses to run if the
  port is taken**; and `app.sh check` shows which process actually serves the
  port before every run.
- JBang runs a script through a launcher chain (`bash` → `java -jar jbang.jar`
  → the app) while it builds, so a pid captured at start can be the launcher. The
  app is identified by its session instead, with JBang's own processes excluded
  from memory.

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
