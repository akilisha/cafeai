#!/bin/bash
# Runs the benchmark suite against one app. Runs on the load generator.
#
#   suite.sh <app-name> <host:port> <out-dir>
#
# wrk2 holds a fixed request rate (-R) and measures latency from when each request
# *should* have been sent, so a stalled server shows up as latency instead of being
# hidden by the client slowing down. Throughput limits show as "achieved < requested".
#
# Every raw wrk2 output is kept, and results.csv gets one line per run. mpstat runs
# for the whole suite; each run's start/end epoch lets CPU be matched to it later.
set -u
APP=$1; HOST=$2; OUT=$3
DUR=${DUR:-30s}
mkdir -p "$OUT"
CSV="$OUT/results.csv"
[ -f "$CSV" ] || echo "app,test,connections,requested_rps,achieved_rps,p50_ms,p99_ms,p999_ms,max_ms,non2xx,socket_errors,start,end" > "$CSV"

mpstat 1 > "$OUT/$APP-loadgen-cpu.txt" &
MPSTAT=$!
trap 'kill $MPSTAT 2>/dev/null' EXIT

# wrk2 prints latencies as 950.00us / 1.23ms / 2.50s / 1.00m; normalise to ms.
ms() { awk -v v="$1" 'BEGIN {
  n = v + 0
  if (v ~ /us$/) n /= 1000; else if (v ~ /ms$/) n += 0; else if (v ~ /m$/) n *= 60000; else if (v ~ /s$/) n *= 1000
  printf "%.2f", n }'; }

run() { # test-name connections rate path
  local name=$1 c=$2 r=$3 path=$4
  local raw="$OUT/$APP-$name-c$c-r$r.txt"
  local start end
  start=$(date +%s)
  wrk2 -t4 -c"$c" -d"$DUR" -R"$r" --latency "http://$HOST$path" > "$raw" 2>&1
  end=$(date +%s)
  local achieved p50 p99 p999 max non2xx sockerr
  achieved=$(awk '/^Requests\/sec:/ {print $2}' "$raw")
  p50=$(ms "$(awk '$1=="50.000%" {print $2; exit}' "$raw")")
  p99=$(ms "$(awk '$1=="99.000%" {print $2; exit}' "$raw")")
  p999=$(ms "$(awk '$1=="99.900%" {print $2; exit}' "$raw")")
  max=$(ms "$(awk '$1=="100.000%" {print $2; exit}' "$raw")")
  non2xx=$(awk '/Non-2xx or 3xx responses:/ {print $NF}' "$raw"); non2xx=${non2xx:-0}
  sockerr=$(awk '/Socket errors:/ {gsub(",",""); print $4+$6+$8+$10}' "$raw"); sockerr=${sockerr:-0}
  echo "$APP,$name,$c,$r,$achieved,$p50,$p99,$p999,$max,$non2xx,$sockerr,$start,$end" | tee -a "$CSV"
}

# Warm-up: let the JIT compile the hot paths before anything is measured.
DUR=30s run warmup-json 64 20000 /json > /dev/null
DUR=30s run warmup-block 1000 9000 "/block?ms=100" > /dev/null

# Raw overhead: a small JSON response at rising request rates.
for r in 20000 40000 60000 80000 100000 120000; do
  run json 256 "$r" /json
done

# The virtual-thread case: every request blocks 100 ms. Each connection can do at
# most 10 requests/s, so ask for 90% of that ceiling at each concurrency.
for c in 1000 2000 4000 8000; do
  run block100 "$c" $((c * 9)) "/block?ms=100"
done
