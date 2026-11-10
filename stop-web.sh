#!/usr/bin/env bash
# Zastavi llamalite backend + pripadny llama-server.
HERE="$(cd "$(dirname "$0")" && pwd)"
PIDF="$HERE/.state/web.pid"
[ -f "$PIDF" ] && kill -9 "$(cat "$PIDF")" 2>/dev/null && rm -f "$PIDF"
for p in /proc/[0-9]*; do
  c=$(tr '\0' ' ' < "$p/cmdline" 2>/dev/null)
  case "$c" in *llama-server*) kill -9 "${p#/proc/}" 2>/dev/null;; esac
done
echo "web + llama-server zastavene"