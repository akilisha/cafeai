#!/bin/bash
# Compares the headers the spike (CafeAI + npm middleware via GraalJS) and real
# Express send for the same requests. Ignores headers that legitimately differ
# between servers (Date, Connection, Keep-Alive, ETag, X-Powered-By, and the
# body's Content-Length / Content-Type).
#   compare.sh <spike-port> <express-port>
S=$1; E=$2
IGNORE='^(date|connection|keep-alive|etag|x-powered-by|content-length|content-type):'
norm() { tr -d '\r' | tail -n +1 | grep -v '^$' | awk 'NR==1 {print; next} {i=index($0,":"); print tolower(substr($0,1,i-1)) substr($0,i)}' | grep -viE "$IGNORE" | sort; }
check() { # name, curl args...
  local name=$1; shift
  diff <(curl -s -o /dev/null -D - "$@" "http://localhost:$S/json" | norm) \
       <(curl -s -o /dev/null -D - "$@" "http://localhost:$E/json" | norm) > /tmp/diff.$$ \
    && echo "SAME   $name ($(curl -s -o /dev/null -D - "$@" "http://localhost:$S/json" | norm | wc -l) lines)" \
    || { echo "DIFFER $name  (< spike, > express)"; cat /tmp/diff.$$; }
}
check "GET, allowed origin" -H "Origin: https://app.example.com"
check "GET, foreign origin" -H "Origin: https://evil.example.org"
check "GET, no origin"
check "OPTIONS preflight" -X OPTIONS -H "Origin: https://app.example.com" -H "Access-Control-Request-Method: PUT" -H "Access-Control-Request-Headers: X-Custom, Content-Type"
rm -f /tmp/diff.$$
