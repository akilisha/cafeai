# Moonshots

Ideas big enough to get CafeAI noticed. Each one has to pass one test: can it be
shown in a single blog post, a single tweet, or ten seconds of someone's terminal?
Ordinary features make people stay; these are meant to make people look.

| # | Moonshot | Status |
|---|----------|--------|
| 1 | [The ten-second demo (JBang)](#1-the-ten-second-demo-jbang) | **Done** |
| 2 | [Benchmarks that settle the argument](#2-benchmarks-that-settle-the-argument) | Rounds 1–4 done (7 frameworks, capped heaps, Vert.x/Micronaut) |
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
- **CafeAI does the same work for less CPU**, which is the story behind both
  ceilings. (This run measured 2.6× less CPU per JSON request at 20,000 req/s;
  round 2 shows that low-load figure varies between runs — see its correction.)
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
- The workstation running `run-all.sh` went into Modern Standby twice
  (19:08–19:12 and 19:19–19:41 UTC). The suites carried on, since they run on the
  load generator and the ssh sessions survived, but the next command waited for
  the laptop to wake. A dropped ssh session would have killed a suite mid-run.
  Next round: run the driver on the load generator itself, under `nohup`.

#### Results

Raw files: `bench/results/2026-10-01-do-c4-run2`. Every ceiling is the server's
CPU (95–100%); the load generator never passed 70%. No run had a connect error or
a non-2xx response. Memory is RSS, averaged over the run.

**Raw overhead — `GET /json`, 256 connections:**

| | Ceiling (req/s) | p99 at 40,000 req/s | Memory at the ceiling | Idle memory |
|---|---|---|---|---|
| **CafeAI** | **~97,000** | **2.8 ms** | 341 MB | 129 MB |
| Gin | ~70,000 | 4.6 ms | **25 MB** | **9 MB** |
| Javalin | ~55,000 | 4.8 ms | 624 MB | 143 MB |
| Spring MVC, tuned | ~51,000 | 4.6 ms | 365 MB | 185 MB |
| Spring MVC | ~49,000 | 12.7 ms | 375 MB | 185 MB |
| Spring WebFlux | ~44,000 | 1,170 ms (already saturating) | 423 MB | 179 MB |
| Express (4 workers) | ~31,000 | saturated at 31,000 | 636 MB | 328 MB |

**Every request waits 100 ms, 4,000 connections** (36,000 req/s requested):

| | Achieved | p50 | p99 | Memory |
|---|---|---|---|---|
| **CafeAI** | **32,808/s** | **108 ms** | **150 ms** | 691 MB |
| **Gin** | **32,796/s** | **104 ms** | **164 ms** | **188 MB** |
| Javalin | 30,000/s | 1.96 s | 2.84 s | 1,469 MB |
| Spring WebFlux | 26,235/s | 3.87 s | 6.05 s | 870 MB |
| Express | 25,649/s, 231 timeouts | 3.87 s | 8.47 s | 897 MB |
| Spring MVC, tuned | 19,602/s | 8.15 s | 11.9 s | 2,230 MB |
| Spring MVC | 18,898/s | 8.03 s | 12.1 s | 2,199 MB |

**Every request waits 100 ms, 8,000 connections** (72,000 req/s requested):

| | Achieved | Timeouts | Memory |
|---|---|---|---|
| **Gin** | **41,658/s** | 0 | **363 MB** |
| **CafeAI** | **39,986/s** | 0 | 1,103 MB |
| Javalin | 29,604/s | 0 | 1,891 MB |
| Spring WebFlux | 27,487/s | 21,320 | 900 MB |
| Express | 25,834/s | 27,137 | 948 MB |
| Spring MVC, tuned | 16,185/s | 8,266 | 2,248 MB |
| Spring MVC | 15,587/s | 15,103 | 2,226 MB |

**Reading it:**

- **On throughput, CafeAI leads the JVM field and the whole field on plain JSON**,
  ~1.4× Gin and ~1.8–2× the other JVM frameworks. On waiting requests it ties Gin
  — the two are the only ones still near the 100 ms floor at 4,000 connections.
- **On memory, Gin is in a different class**: 9 MB idle, 25 MB at 70,000 req/s,
  363 MB holding 8,000 waiting requests. CafeAI needs 3–14× more depending on the
  load. That is mostly the JVM, not CafeAI — every JVM contender starts at
  130–185 MB — but it is the honest price of the JVM.
- **Among the JVMs, CafeAI has the lowest memory at every blocking level** (691 MB
  at 4,000 connections, against 870 MB to 2.2 GB), and Spring MVC goes to the
  2 GB default heap ceiling, where garbage collection eats the CPU the requests
  needed — likely much of why it falls over there.
- **JVM memory is partly a choice.** RSS shows what each JVM grew to with a 2 GB
  heap available; it would run in less if told to. The next test should cap the
  heap (say 256 MB and 512 MB) and see who still holds up.
- **Repeatability:** CafeAI and Spring MVC came out within ~3% of round 1 on every
  ceiling.
- **A correction to round 1:** its "2.6× less CPU per JSON request" compared one
  low-load sample (19.7% vs 51.5% at 20,000 req/s). Round 2 measured 35.8% vs
  50.3% at the same rate, so CPU at low load varies run to run and that ratio is
  not reliable. The ceilings are the solid figure: ~97–99k vs ~49–53k req/s.

### Round 3: the JVMs on a capped heap (2026-10-01)

Round 2's JVM memory was what each JVM grew to with ~2 GB of heap on offer — its
appetite, not its need. Round 3 reruns the five JVM configurations with the heap
capped at 256 MB and at 512 MB (`-Xmx256m` / `-Xmx512m`), the sizes a team picks
for a small container, plus `-XX:+ExitOnOutOfMemoryError` so a JVM that runs out
exits and the run shows it. Express and Gin are not JVMs; their round 2 numbers
stand as the reference. Raw files: `bench/results/2026-10-01-do-c4-run3`.

This round's driver ran on the load generator under `nohup`
(`SV=<server private ip> LG=127.0.0.1`), with its own ssh key to the server, so
the workstation could sleep.

**Who survived.** A run "died" when the app's memory samples stop mid-suite;
the surviving Spring MVC and Javalin logs show the cause:
`Terminating due to java.lang.OutOfMemoryError: Java heap space` (the kernel
killed nothing).

| | 256 MB | 512 MB |
|---|---|---|
| **CafeAI** | **survived everything** | **survived everything** |
| Spring WebFlux | survived | survived |
| Javalin | died at 4,000 waiting requests | died at 8,000 |
| Spring MVC | died at 4,000 | died at 8,000 |
| Spring MVC, tuned | died at 4,000 | died at 8,000 |

**Plain JSON barely notices the cap** — each request is done in about a
millisecond, so little is alive at once. Ceilings at 256 MB: CafeAI ~95,700,
Javalin ~51,800, Spring MVC ~48,000–50,900, WebFlux ~39,400 req/s. Holding many
waiting requests is what needs memory, and that is where the field splits.

**Every request waits 100 ms, 4,000 connections, 512 MB heap:**

| | Achieved | p50 | Memory (RSS) |
|---|---|---|---|
| **CafeAI** | **32,773/s** | **134 ms** | 568 MB |
| Spring WebFlux | 22,270/s | 6.75 s | 595 MB |
| Javalin | 12,899/s | 12.0 s | 667 MB |
| Spring MVC | 8,207/s, 7,614 timeouts | 14.7 s | 741 MB |
| Spring MVC, tuned | 7,663/s, 9,920 timeouts | 14.9 s | 718 MB |
| *Gin (no cap, round 2)* | *32,796/s* | *104 ms* | *188 MB* |
| *Express (no cap, round 2)* | *25,649/s, 231 timeouts* | *3.87 s* | *897 MB* |

**CafeAI across heap sizes, waiting requests:**

| | 256 MB | 512 MB | default (~2 GB) |
|---|---|---|---|
| 2,000 connections | 17,095/s, RSS 364 MB | 17,095/s, RSS 348 MB | 17,095/s, RSS 404 MB |
| 4,000 connections | 27,260/s, p50 3.5 s | **32,773/s, p50 134 ms** | 32,808/s, p50 108 ms |
| 8,000 connections | 9,649/s, 1,904 timeouts | 30,025/s, 0 errors | 39,986/s, 0 errors |

**Reading it:**

- **At 512 MB, CafeAI gives up nothing up to 4,000 waiting requests** — the same
  32,800 req/s as with 2 GB, in 568 MB of RSS instead of 691 MB — and still serves
  8,000 with no errors. At 256 MB it holds 2,000 comfortably and degrades beyond,
  but never falls over.
- **Every other virtual-thread JVM falls over.** Javalin and both Spring MVC
  configurations exhaust the heap at 4,000 (256 MB) or 8,000 (512 MB) waiting
  requests. Before they die they slow to medians of 12–15 s: the garbage
  collector is fighting for space.
- **WebFlux survives but slows** — 22,270 req/s and a 6.75 s median at 4,000 on
  512 MB. A reactive pipeline holds a waiting request in a small object rather
  than a thread, which keeps it alive; it is just not fast.
- **Against Gin, the honest comparison is now: same throughput, ~3× the memory.**
  At 4,000 waiting requests CafeAI on 512 MB matches Gin's 32,800 req/s with
  568 MB of RSS to Gin's 188 MB. With 2 GB on offer the gap looked like 3.7×; most
  of that was the JVM using what it was given.
- **The CafeAI memory leak would have been fatal here.** Any of these caps would
  have killed 0.5.0 within seconds of steady traffic.

**Caveats:** one run per configuration; RSS for a capped JVM still includes
metaspace, JIT-compiled code and thread structures beyond the heap (hence 434 MB
of RSS on a 256 MB heap); each JVM used its default collector (G1).

**Next round:** each run now keeps its own app log (`<app>-app.log`), so a crash
is recorded directly instead of inferred from where its memory samples stop —
this round, logs were overwritten by the next run of the same app.

### Round 4: head-to-head with Vert.x and Micronaut (2026-10-01)

Spring and Express are not CafeAI's natural rivals; the lean, performance-minded
JVM frameworks are. Round 4 puts CafeAI against **Vert.x 5.2.0** and **Micronaut
5.2.1** on every measure so far — the JSON and waiting-request ladders, CPU, idle
and loaded memory — at the default heap and capped at 512 MB and 256 MB. CafeAI
runs again in the same session rather than reusing earlier numbers, and the nine
runs are interleaved (all three at default, then all three at 512 MB, then all
three at 256 MB) so any drift in the droplets lands on everyone alike.

**How each is written** — the way its own documentation would have it:

- **Vert.x**: non-blocking handlers on event loops, one verticle instance per
  core (`DeploymentOptions.setInstances`), the wait is `vertx.setTimer`.
- **Micronaut**: `/json` on the Netty event loop (Micronaut's default for a plain
  controller); `/block` on virtual threads with `Thread.sleep` via
  `@ExecuteOn(TaskExecutors.VIRTUAL)` — the same model as CafeAI, so the most
  direct comparison of the three.
- Micronaut generates code at compile time with annotation processors, and since
  JDK 23 `javac` runs none unless asked, so its script carries
  `//COMPILE_OPTIONS -proc:full`.

Each app was started once on the server before the run, which warms JBang's build
cache (no compile time in the first measured run) and confirms through
`app.sh check` that it, and nothing else, serves the port. Idle memory at that
start: Vert.x 104 MB, CafeAI 117 MB, Micronaut 129 MB.

#### Results

Raw files: `bench/results/2026-10-01-do-c4-run4`. All nine runs survived — no
`OutOfMemoryError`, and the only timeouts were 140 for CafeAI on 256 MB at 8,000
waiting requests.

**Raw overhead — `GET /json`, 256 connections, default heap:**

| | Ceiling (req/s) | Server CPU there | Memory there |
|---|---|---|---|
| **Vert.x** | **≥ 112,000** (not reached) | 88% | **250 MB** |
| Micronaut | ~101,000 | 99% | 368 MB |
| CafeAI | ~96,000 | 99% | 332 MB |

Vert.x's ceiling was not found: at the suite's top rate (120,000 req/s) it served
112,000 with its CPU 88% busy. The rig cannot ask for more, so its real ceiling
is higher. Capped at 512 / 256 MB: Vert.x ~106–108k, CafeAI ~94–97k, Micronaut
~89–90k.

**Every request waits 100 ms — 4,000 and 8,000 connections:**

| | 4,000 conns | 8,000 conns | Memory at 8,000 |
|---|---|---|---|
| **Vert.x** (`setTimer`, event loop) | **32,815/s, p50 101 ms, p99 111 ms** | **55,365/s, p50 151 ms** | **444 MB** |
| CafeAI (`Thread.sleep`, virtual threads) | 32,762/s, p50 161 ms, p99 637 ms | 39,569/s, p50 6.7 s | 921 MB |
| Micronaut (`Thread.sleep`, virtual threads) | 31,535/s, p50 861 ms, p99 2.8 s | 36,634/s, p50 7.5 s | 1,064 MB |

At 512 MB and 256 MB Vert.x barely moves (32,814/s at 4,000 on both; 50–53k/s at
8,000), CafeAI holds 32,538/s at 4,000 on 512 MB but drops to 26,341/s on 256 MB,
and Micronaut falls to 28,864/s (512 MB) and 17,748/s (256 MB).

**Reading it — the plain version:**

- **Vert.x beats CafeAI on every measure here**: more throughput, lower latency,
  half the memory under load, and it shrugs off a 256 MB heap. A request waiting
  on a timer in Vert.x costs a small callback object; in CafeAI it costs a parked
  virtual thread with its stack. That is the price of the programming model.
- **That model is the real difference, and the test favours Vert.x on it.**
  Vert.x waited with a non-blocking timer. A Vert.x app making a real *blocking*
  call — JDBC, a blocking HTTP client — must not do it on the event loop; it hands
  it to a worker pool (20 threads by default) or uses Vert.x's reactive clients
  instead. CafeAI's claim is that you can write the blocking code and still get
  this class of throughput, and against Micronaut — the same virtual-thread model
  — it does: equal or better throughput at 4,000 and 8,000 waiting requests, a
  fifth of Micronaut's median at 4,000, and less memory.
- **Plain JSON: CafeAI is third of three.** Close to Micronaut (~96k vs ~101k),
  clearly behind Vert.x (112k and more). Round 2's "highest JSON ceiling of the
  whole field" was true of that field, which did not include these two.
- **Run-to-run variance:** CafeAI's p50 at 4,000 waiting requests was 108 ms in
  round 2 and 161 ms here; throughput agreed within 0.2%. Medians near
  saturation move more than throughput does.

**The fair next test:** Vert.x running the same *blocking* code — its
virtual-thread verticles (`ThreadingModel.VIRTUAL_THREAD`) with `Thread.sleep`,
and `executeBlocking` on its worker pool — which is the comparison CafeAI's
"write blocking code" pitch actually rests on.

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
