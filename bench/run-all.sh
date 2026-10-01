#!/bin/bash
# Runs the whole suite for every app, one after another. Runs from a workstation.
#
#   SV=<server public ip> LG=<loadgen public ip> PRIVATE=<server private ip> \
#   KEY=~/.ssh/<key> bench/run-all.sh <run-name> [app ...]
#
# For each app: start it on the server, record server CPU (mpstat) and the app's
# memory (app.sh memwatch) while bench/load/suite.sh drives it from the load
# generator, then stop it. Results land in /root/<run-name> on both machines.
set -u
RUN=$1; shift
APPS=("$@")
[ ${#APPS[@]} -eq 0 ] && APPS=(cafeai springmvc springmvc-tuned webflux javalin express gin)
SSH="ssh -i $KEY -o ConnectTimeout=10 -o BatchMode=yes"

for app in "${APPS[@]}"; do
  # A "-x<MB>" suffix caps the JVM heap, and makes running out of it exit the JVM
  # (instead of limping on), so the run shows it: cafeai-x256 = cafeai, -Xmx256m.
  base=$app; heap=""
  if [[ $app =~ ^(.*)-x([0-9]+)$ ]]; then
    base=${BASH_REMATCH[1]}
    heap="-Xmx${BASH_REMATCH[2]}m -XX:+ExitOnOutOfMemoryError"
  fi
  case $base in
    springmvc-tuned) START="springmvc -Dserver.tomcat.max-keep-alive-requests=-1 -Dserver.tomcat.max-connections=20000 -Dserver.tomcat.accept-count=10000" ;;
    *)               START=$base ;;
  esac
  START="$START $heap"
  echo "=== $app ($(date -u +%H:%M:%S))"
  $SSH root@"$SV" "mkdir -p /root/$RUN && ./app.sh start $START" || { echo "$app did not start, skipping"; continue; }
  $SSH root@"$SV" "./app.sh check; \
    nohup ./app.sh memwatch /root/$RUN/$app-server-mem.txt > /dev/null 2>&1 & \
    nohup mpstat 1 > /root/$RUN/$app-server-cpu.txt 2>&1 & echo \$! > /root/mpstat.pid"
  $SSH root@"$LG" "./suite.sh $app $PRIVATE:8080 /root/$RUN"
  $SSH root@"$SV" "kill \$(cat /root/mpstat.pid); ./app.sh stop"
done
