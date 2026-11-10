#!/usr/bin/env bash
# ============================================================
#  llamalite - spusti backend (web + API) na pozadi
#     bash serve.sh            # port 8181
#     PORT=9000 bash serve.sh
#  Vypnutie:  bash stop-web.sh
# ============================================================
set -u
HERE="$(cd "$(dirname "$0")" && pwd)"
STATE="$HERE/.state"
mkdir -p "$STATE"
LOG="$STATE/web.log"
PIDF="$STATE/web.pid"
PORT="${PORT:-8181}"

[ -f "$PIDF" ] && kill -9 "$(cat "$PIDF")" 2>/dev/null

cd "$HERE/scripts"
PORT="$PORT" setsid nohup python3 backend.py > "$LOG" 2>&1 < /dev/null &
echo $! > "$PIDF"
disown 2>/dev/null
sleep 1

echo "llamalite web:  http://127.0.0.1:$PORT"
echo "PID: $(cat "$PIDF")   log: $LOG"
echo "Model potom startuj tlacidlom Start v prehliadaci."