#!/usr/bin/env bash
# Fake Manhunt server runner.
# Every start restores the world from snapshot/ (the last /save), so nothing
# survives unless you /save. /load in-game stops the server and this loop
# restores + restarts it. Typing "stop" in the console exits for real.
cd "$(dirname "$0")"
while true; do
  if [ -d snapshot ]; then
    echo "[start.sh] Restoring world from last /save..."
    for d in snapshot/*/; do
      name=$(basename "$d")
      rm -rf "./$name"
      cp -a "snapshot/$name" "./$name"
    done
  fi
  rm -f restart.flag
  java -Xms1G -Xmx2G -jar paper.jar --nogui
  if [ -f restart.flag ]; then
    echo "[start.sh] /load requested, restarting..."
    sleep 2
    continue
  fi
  break
done
