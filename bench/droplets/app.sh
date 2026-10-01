#!/bin/bash
# Starts and stops one benchmark app on the server.
#
#   app.sh start <name> [extra JVM options...]   # /root/<name>.java, /root/express, or /root/gin
#   app.sh stop
#   app.sh check <name>     # prints the processes serving port 8080
#   app.sh memwatch <file>  # every second: "<epoch> <rss_kb>" summed over the app's processes
#
# Each app runs in its own session (setsid), so "the app" is every process in that
# session: an Express primary and its workers, or a JVM -- but not JBang's own
# launcher, which is excluded from memory. Stop kills the whole session, then
# anything still on the port. Start refuses if the port is already taken, so a
# leftover server can never answer for the app being measured.
#
# (pkill -f over ssh is avoided: the pattern matches the ssh command itself.)
set -u
SIDFILE=/root/app.sid

app_pids() { # pids in the app's session, minus shells and JBang's launcher
  local sid; sid=$(cat "$SIDFILE")
  ps -eo pid=,sid=,comm=,args= | awk -v s="$sid" '$2 == s && $3 != "bash" && $0 !~ /jbang\.jar/ {print $1}'
}

case $1 in
  start)
    NAME=$2; shift 2
    if ss -ltn "sport = :8080" | grep -q LISTEN; then
      echo "port 8080 is already in use:"; ss -ltnp "sport = :8080"; exit 1
    fi
    cd /root
    if [ -f "/root/$NAME.java" ]; then
      RUNTIME=()
      for opt in "$@"; do RUNTIME+=("-R$opt"); done
      setsid nohup jbang "${RUNTIME[@]}" "$NAME.java" > "/root/$NAME.log" 2>&1 < /dev/null &
    elif [ "$NAME" = express ]; then
      (cd /root/express && exec setsid nohup node server.js > /root/express.log 2>&1 < /dev/null) &
    elif [ "$NAME" = gin ]; then
      (cd /root/gin && exec setsid nohup ./gin > /root/gin.log 2>&1 < /dev/null) &
    else
      echo "unknown app $NAME"; exit 1
    fi
    echo $! > "$SIDFILE"
    for _ in $(seq 120); do
      curl -s -o /dev/null http://localhost:8080/json && { echo "$NAME up, session $(cat $SIDFILE)"; exit 0; }
      sleep 1
    done
    echo "$NAME did not start; see /root/$NAME.log"; exit 1 ;;
  stop)
    [ -f "$SIDFILE" ] && pkill -s "$(cat $SIDFILE)" 2>/dev/null
    for _ in $(seq 30); do ss -ltn "sport = :8080" | grep -q LISTEN || break; sleep 1; done
    fuser -k 8080/tcp > /dev/null 2>&1
    rm -f "$SIDFILE"; echo stopped ;;
  check)
    echo "serving 8080: $(ss -ltnp 'sport = :8080' | grep -oE 'pid=[0-9]+' | sort -u | tr '\n' ' ')"
    for p in $(app_pids); do ps -o pid=,rss=,args= -p "$p" | cut -c1-90; done ;;
  memwatch)
    while [ -f "$SIDFILE" ]; do
      PIDS=$(app_pids | paste -sd,)
      [ -z "$PIDS" ] && break
      echo "$(date +%s) $(ps -o rss= -p "$PIDS" | awk '{s += $1} END {print s + 0}')"
      sleep 1
    done > "$2" ;;
esac
