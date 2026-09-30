#!/bin/sh
# Match start.sh: sh callers are re-executed with Bash before Bash syntax is parsed.
if [ -z "${BASH_VERSION:-}" ]; then
    command -v bash >/dev/null 2>&1 || { echo "Bash is required" >&2; exit 1; }
    exec bash "$0" "$@"
fi
set -euo pipefail
APP_DIR=${APP_DIR:-/opt/logmonitor/backend}
[[ $APP_DIR =~ ^/[A-Za-z0-9._/-]+$ ]] || { echo "APP_DIR must be a safe absolute path" >&2; exit 2; }
[[ $# == 0 ]] || { echo "Usage: $0" >&2; exit 2; }
CONTROL_PATH="$APP_DIR/logmonitor-backend.sh"
[[ -f $CONTROL_PATH ]] || { echo "Backend is not installed at $APP_DIR" >&2; exit 1; }
# Existing control logic checks PID identity and performs the bounded graceful stop.
exec bash "$CONTROL_PATH" stop
