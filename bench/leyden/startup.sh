#!/bin/bash
# Measures startup with and without a JDK 25 AOT cache (Project Leyden) for one
# app. Runs on the server, after `app.sh start <app>` has built it once.
#
#   startup.sh <app> [runs]
#
# 1. Captures the exact java command JBang ends up running, so JBang's own
#    startup is not timed.
# 2. Baseline: <runs> cold starts, timed from process start to the first 200 on
#    /json, with the app's RSS at that moment.
# 3. Training: starts with -XX:AOTCacheOutput, drives /json, /block and /thread,
#    then stops it with SIGTERM; the JVM writes the cache as it exits.
# 4. Cached: <runs> starts with -XX:AOTMode=on -XX:AOTCache=..., where AOTMode=on
#    makes the JVM fail rather than quietly ignore an unusable cache.
#
# Prints one line per start and writes /root/leyden/<app>.csv.
set -u
APP=$1; RUNS=${2:-5}
OUT=/root/leyden; mkdir -p "$OUT"
CMD="$OUT/$APP.cmd"; AOT="$OUT/$APP.aot"; CSV="$OUT/$APP.csv"

# 1. The real java command line, captured from a JBang-started instance.
/root/app.sh start "$APP" > /dev/null || exit 1
PID=$(ss -ltnp 'sport = :8080' | grep -oE 'pid=[0-9]+' | head -1 | cut -d= -f2)
tr '\0' '\n' < "/proc/$PID/cmdline" > "$CMD"
/root/app.sh stop > /dev/null
JAVA=$(head -1 "$CMD"); mapfile -t ARGS < <(tail -n +2 "$CMD")

start_once() { # label, extra JVM options... -> prints "label,ms,rss_mb"
  local label=$1; shift
  local t0 t1 pid
  t0=$(date +%s%N)
  "$JAVA" "$@" "${ARGS[@]}" > "$OUT/$APP-$label.log" 2>&1 &
  pid=$!
  until curl -s -o /dev/null -m 1 http://localhost:8080/json; do
    kill -0 "$pid" 2>/dev/null || { echo "$label,failed,"; return 1; }
    sleep 0.005
  done
  t1=$(date +%s%N)
  local rss; rss=$(( $(ps -o rss= -p "$pid") / 1024 ))
  kill "$pid"; wait "$pid" 2>/dev/null
  echo "$label,$(( (t1 - t0) / 1000000 )),$rss"
}

train() { # drive the code paths the cache should know about
  for _ in $(seq 400); do curl -s -o /dev/null http://localhost:8080/json; done
  for _ in $(seq 100); do curl -s -o /dev/null "http://localhost:8080/block?ms=1"; done
  for _ in $(seq 20); do curl -s -o /dev/null http://localhost:8080/thread; done
}

echo "mode,ms_to_first_response,rss_mb" > "$CSV"

# 2. Baseline
for _ in $(seq "$RUNS"); do start_once baseline | tee -a "$CSV"; done

# 3. Training run
rm -f "$AOT"
"$JAVA" -XX:AOTCacheOutput="$AOT" "${ARGS[@]}" > "$OUT/$APP-training.log" 2>&1 &
TPID=$!
until curl -s -o /dev/null -m 1 http://localhost:8080/json; do sleep 0.05; done
train
kill "$TPID"
for _ in $(seq 120); do kill -0 "$TPID" 2>/dev/null || break; sleep 1; done
[ -f "$AOT" ] || { echo "no AOT cache written; see $OUT/$APP-training.log"; exit 1; }
echo "cache: $(du -h "$AOT" | cut -f1)"

# 4. With the cache
for _ in $(seq "$RUNS"); do start_once cached -XX:AOTMode=on -XX:AOTCache="$AOT" | tee -a "$CSV"; done
