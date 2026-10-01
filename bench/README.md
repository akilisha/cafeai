# CafeAI benchmarks — runbook

Everything needed to rerun the web-framework benchmarks from scratch: two
cloud machines, the apps, the load suite, the analysis, and every raw result so
far. The story — what was measured, what it showed, what went wrong — is in
[`docs/MOONSHOT.md`](../docs/MOONSHOT.md) (moonshots #2 and #3). This file is how
to do it again.

## What is here

| Path | What it is |
|---|---|
| `apps/` | The same tiny app in every contender: `GET /json`, `GET /block?ms=N` (wait N ms the framework's own idiomatic way), `GET /thread` (is the handler on a virtual thread?). `cafeai`, `springmvc`, `webflux`, `javalin`, `vertx`, `micronaut` are JBang scripts; `express/` and `gin/` are Node and Go apps. |
| `droplets/server-init.sh`, `droplets/loadgen-init.sh` | Boot scripts (cloud-init user data) for the two machines. |
| `droplets/app.sh` | Starts, stops, checks and memory-samples one app on the server. |
| `load/suite.sh` | The load suite, run on the load generator against one app. |
| `run-all.sh` | Runs the suite for a list of apps, one after another, collecting CPU and memory. |
| `leyden/startup.sh` | Startup timing with and without a JDK 25 AOT cache (moonshot #3). |
| `load/cpu.py` | Matches each run to CPU on both machines and the app's memory. |
| `load/ramp.js` | k6 script from the first laptop pilot (not used on the droplets). |
| `results/` | Raw results of every round, one directory each. |

## The suite

Each app gets a 30 s warm-up on each route, then 30 s runs with
[wrk2](https://github.com/giltene/wrk2) at a **fixed request rate**. wrk2 times
each request from when it *should* have been sent, so a stalled server shows as
latency instead of being hidden by a slower client; a throughput limit shows as
*achieved < requested*.

- **`json`:** `GET /json`, 256 connections, at 20k, 40k, 60k, 80k, 100k, 120k req/s.
- **`block100`:** `GET /block?ms=100`, at 1,000 / 2,000 / 4,000 / 8,000
  connections, each asked for 90% of what its connections could carry
  (9 req/s per connection).

Every run's raw wrk2 output is kept; `results.csv` gets one line per run:
`app,test,connections,requested_rps,achieved_rps,p50_ms,p99_ms,p999_ms,max_ms,non2xx,socket_errors,start,end`.

## 1. Create the machines

Two DigitalOcean **CPU-Optimized** droplets (`c-4`: 4 dedicated vCPU, 8 GB,
$0.125/hour each). Not Basic droplets: those share CPU with other customers.

```bash
doctl auth init                      # once: an API token with write scope
doctl compute ssh-key list           # the id of the key to put on the droplets

# Not every region offers c-4 (nyc3 does not); find one that does:
doctl compute region list -o json | python -c "import json,sys; [print(r['slug']) for r in json.load(sys.stdin) if 'c-4' in r.get('sizes', [])]"

for n in server loadgen; do
  doctl compute droplet create cafeai-bench-$n --region nyc1 --size c-4 \
    --image ubuntu-24-04-x64 --ssh-keys <key-id> --tag-name cafeai-bench \
    --user-data-file bench/droplets/$n-init.sh --enable-monitoring --wait \
    --format Name,PublicIPv4,PrivateIPv4
done
```

The boot scripts raise kernel network limits on both machines; the server gets
Temurin 25, JBang, Node 24 and Go; the load generator gets wrk2 (built from
source) and k6. They take about five minutes; each machine has
`/var/log/bench-ready` when done (`/var/log/bench-setup.log` otherwise).

Below, `SV` / `LG` are the public IPs, `SV_PRIVATE` the server's private IP, and
`KEY` your private key.

## 2. Prepare the server

```bash
ssh -i $KEY root@$SV '
  ufw allow OpenSSH; ufw allow from 10.0.0.0/8 to any port 8080 proto tcp; ufw --force enable
  mkdir -p /root/express /root/gin'
scp -i $KEY bench/apps/*.java bench/droplets/app.sh root@$SV:/root/
scp -i $KEY bench/apps/express/{server.js,package.json,package-lock.json} root@$SV:/root/express/
scp -i $KEY bench/apps/gin/{main.go,go.mod,go.sum} root@$SV:/root/gin/
ssh -i $KEY root@$SV '
  chmod +x app.sh
  cd /root/express && npm ci
  cd /root/gin && go build -o gin .'
```

(The firewall keeps port 8080 off the public internet; widen `10.0.0.0/8` to
your VPC's actual range if it differs.)

**Start every app once before measuring.** It builds each JBang script (so no
run includes compile time) and proves each app — and nothing else — serves the
port:

```bash
ssh -i $KEY root@$SV 'for a in cafeai springmvc webflux javalin vertx micronaut express gin; do
  ./app.sh start $a && ./app.sh check && curl -s localhost:8080/thread; echo; ./app.sh stop; done'
```

**CafeAI's version.** `apps/cafeai.java` pins `cafeai-core:0.5.1`. Do not run
the benchmarks on 0.5.0: it keeps every request in memory and runs out of heap
under sustained load (see `CHANGELOG.md`). The rounds in `results/` ran 0.5.0
with that fix applied — the build 0.5.1 released.

## 3. Let the load generator drive

Run the driver on the load generator, detached, so nothing depends on your
workstation staying awake (a sleeping laptop stalls a laptop-side driver, and a
dropped ssh session kills the suite under it). It needs its own key to the server:

```bash
ssh -i $KEY root@$LG 'ssh-keygen -q -t ed25519 -N "" -f /root/.ssh/bench_key; cat /root/.ssh/bench_key.pub' > lg.pub
ssh -i $KEY root@$SV "cat >> /root/.ssh/authorized_keys" < lg.pub
ssh -i $KEY root@$LG "cat >> /root/.ssh/authorized_keys" < lg.pub      # it also ssh-es to itself
scp -i $KEY bench/load/suite.sh bench/run-all.sh root@$LG:/root/
ssh -i $KEY root@$LG "chmod +x suite.sh run-all.sh
  ssh -i /root/.ssh/bench_key -o StrictHostKeyChecking=accept-new root@$SV_PRIVATE true
  ssh -i /root/.ssh/bench_key -o StrictHostKeyChecking=accept-new root@127.0.0.1 true"
```

## 4. Run

```bash
ssh -i $KEY root@$LG "SV=$SV_PRIVATE LG=127.0.0.1 PRIVATE=$SV_PRIVATE KEY=/root/.ssh/bench_key \
  nohup ./run-all.sh run1 cafeai springmvc webflux javalin express gin > /root/run1.log 2>&1 < /dev/null &"
```

About 6–7 minutes per app. With no app names it runs `cafeai springmvc
springmvc-tuned webflux javalin express gin`. Names can carry suffixes:

| Name | Means |
|---|---|
| `springmvc-tuned` | Spring MVC with `max-keep-alive-requests=-1`, `max-connections=20000`, `accept-count=10000` |
| `<app>-x512` | JVM heap capped at 512 MB, exiting on `OutOfMemoryError` (any number of MB) |
| `<app>-aot` | started with the AOT cache from step 6 (`-XX:AOTMode=on`, so an unusable cache fails) |

Follow it with `ssh root@$LG 'cat /root/run1.log'`. **Leave the server alone
while it runs:** anything else there competes with the app being measured.

For each app the driver starts it, prints `app.sh check` (which process serves
the port), records server CPU (`mpstat 1`) and the app's resident memory
(`app.sh memwatch`, every process in the app's session — an Express primary and
its workers count together), runs the suite, keeps the app's log, and stops it.

## 5. Collect and analyse

```bash
mkdir -p bench/results/<date>-run1
ssh -i $KEY root@$LG 'cd /root/run1 && tar czf /root/lg.tgz *'
ssh -i $KEY root@$SV 'cd /root/run1 && tar czf /root/sv.tgz *'
scp -i $KEY root@$LG:/root/lg.tgz root@$SV:/root/sv.tgz bench/results/<date>-run1/
cd bench/results/<date>-run1 && tar xzf lg.tgz && tar xzf sv.tgz && rm -f lg.tgz sv.tgz

python ../../load/cpu.py cafeai      # per run: achieved rate, CPU on both machines, avg/peak memory
```

What to check before believing a number:

- **Whose limit is it?** A ceiling is the app's only if the *server* CPU is near
  100% and the load generator's is not. If the load generator is the busy one
  (or neither is), the rig is the limit — say so.
- **Did the app survive?** A crashed app shows as huge socket-error counts, then
  runs with no achieved rate (`cpu.py` prints `server gone`). Its memory samples
  stop at the moment it died, and `<app>-app.log` holds the reason (e.g.
  `Terminating due to java.lang.OutOfMemoryError`).
- **Timeouts vs refusals:** wrk2's `Socket errors: connect` means refused (accept
  queue full); `timeout` means the server answered too slowly (overloaded).
- **One run is one sample.** Repeat before quoting; throughput repeats within a
  few percent, medians near saturation move more.

## 6. Startup and the AOT cache (moonshot #3)

On the server, for each app, after it has been started once:

```bash
scp -i $KEY bench/leyden/startup.sh root@$SV:/root/
ssh -i $KEY root@$SV 'chmod +x startup.sh; for a in cafeai vertx micronaut springmvc; do ./startup.sh $a 5; done'
```

It captures the exact `java` command JBang runs (JBang's own startup is not
timed), times five cold starts to the first 200 on `/json`, trains a cache with
`-XX:AOTCacheOutput` (driving `/json`, `/block`, `/thread`, then SIGTERM so the
JVM writes it), and times five starts with the cache. Results:
`/root/leyden/<app>.csv`; caches: `/root/leyden/<app>.aot`, which `<app>-aot`
runs in step 4 use. A cache is tied to the exact JDK and classpath — retrain
after changing either.

## 7. Tear down

```bash
doctl compute droplet delete --tag-name cafeai-bench --force
doctl compute droplet list          # confirm nothing is left billing
```

The pair costs $0.25/hour; a full round of seven apps is about 50 minutes.

## Rules learned the hard way

- **Check what is actually serving the port** before trusting a run. A leftover
  server from an earlier run once answered every request in a smoke test.
  `app.sh start` now refuses if the port is taken.
- **Never `pkill -f <pattern>` over ssh:** the pattern matches the ssh command
  running it, and kills that instead. `app.sh` tracks the app's session.
- **Ramp, don't stampede:** thousands of connections opened in the same instant
  overflow the accept queue, and refused clients retry in a tight loop that
  inflates failure counts enormously.
- **wrk2 calibrates for ~10 s;** shorter runs are meaningless, and `cpu.py` skips
  each run's first 12 s.
- **Scripts that run on Linux need LF line endings** (`.gitattributes` enforces it
  for `*.sh`); a CRLF script fails with a misleading "No such file or directory".
- **From Git Bash on Windows**, set `MSYS_NO_PATHCONV=1` when passing URL paths to
  tools, or `/json` becomes a Windows file path — and then pass script paths in
  Windows form (`cygpath -m`).
- **A test can pass for the wrong reason.** Make each check sensitive to the
  thing it claims to test (see the false pass in `MOONSHOT.md` #11).
