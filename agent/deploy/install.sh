#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
VERSION=$(tr -d '[:space:]' < "$SCRIPT_DIR/VERSION")
DESTDIR=${DESTDIR:-}
APP_USER=${APP_USER:-${SUDO_USER:-}}
APP_GROUP=${APP_GROUP:-}
APP_DIR=${APP_DIR:-/opt/logmonitor-agent}
CONFIG_DIR=${CONFIG_DIR:-/etc/logmonitor-agent}
DATA_DIR=${DATA_DIR:-/var/lib/logmonitor-agent}
LEGACY_SERVICE_DIR=${LEGACY_SERVICE_DIR:-/etc/systemd/system}
JAVA_BIN=${JAVA_BIN:-/usr/bin/java}
CONFIGURE_AGENT=${CONFIGURE_AGENT:-false}
ALLOW_HTTP=${ALLOW_HTTP:-false}
START_PROCESS=${START_PROCESS:-${ENABLE_SERVICE:-false}}

require_commands() {
    local missing=()
    local command_name
    for command_name in "$@"; do
        command -v "$command_name" >/dev/null 2>&1 || missing+=("$command_name")
    done
    if ((${#missing[@]})); then
        echo "Missing required command(s): ${missing[*]}" >&2
        echo "Install the packages providing these commands and run install.sh again." >&2
        return 1
    fi
}

java_major() {
    local value
    value=$($JAVA_BIN -version 2>&1 | sed -n '1s/.*version "\([^"]*\)".*/\1/p')
    if [[ $value == 1.8.* ]]; then echo 8; else echo "${value%%.*}"; fi
}

validate_path() {
    local name=$1 value=$2
    [[ $value =~ ^/[A-Za-z0-9._/-]+$ ]] || { echo "$name must be a safe absolute path: $value" >&2; return 2; }
}

preflight_agent_for_user() {
    local preflight_root
    preflight_root=$(mktemp -d)
    install -m 0600 "$CONFIG_PATH_FILE" "$preflight_root/agent.json"
    install -d -m 0700 "$preflight_root/data"
    chown -R "$APP_USER:$APP_GROUP" "$preflight_root"
    if ! runuser -u "$APP_USER" -- "$JAVA_BIN" -jar "$SCRIPT_DIR/logmonitor-agent.jar" \
        check --config "$preflight_root/agent.json" --data "$preflight_root/data"; then
        rm -rf "$preflight_root"
        return 1
    fi
    rm -rf "$preflight_root"
}

[[ -f $SCRIPT_DIR/logmonitor-agent.jar ]] || { echo "Missing logmonitor-agent.jar" >&2; exit 1; }
[[ -f $SCRIPT_DIR/logmonitor-agent.sh.template ]] || { echo "Missing logmonitor-agent.sh.template" >&2; exit 1; }
[[ -z $DESTDIR || $DESTDIR == /* ]] || { echo "DESTDIR must be absolute" >&2; exit 2; }
if [[ -n $DESTDIR ]]; then
    APP_USER=${APP_USER:-logmonitor}
    APP_GROUP=${APP_GROUP:-logmonitor}
fi
[[ -z $APP_USER || $APP_USER =~ ^[a-z_][a-z0-9_-]*$ ]] &&
    [[ -z $APP_GROUP || $APP_GROUP =~ ^[a-z_][a-z0-9_-]*$ ]] || {
    echo "Invalid service user or group" >&2
    exit 2
}
[[ $CONFIGURE_AGENT == true || $CONFIGURE_AGENT == false ]] || { echo "CONFIGURE_AGENT must be true or false" >&2; exit 2; }
[[ $ALLOW_HTTP == true || $ALLOW_HTTP == false ]] || { echo "ALLOW_HTTP must be true or false" >&2; exit 2; }
[[ $START_PROCESS == true || $START_PROCESS == false ]] || { echo "START_PROCESS must be true or false" >&2; exit 2; }
validate_path APP_DIR "$APP_DIR"
validate_path CONFIG_DIR "$CONFIG_DIR"
validate_path DATA_DIR "$DATA_DIR"
validate_path LEGACY_SERVICE_DIR "$LEGACY_SERVICE_DIR"
validate_path JAVA_BIN "$JAVA_BIN"
require_commands install rm sed mktemp tr uname id dirname

if [[ -z $DESTDIR ]]; then
    [[ $(uname -s) == Linux ]] || { echo "LogMonitor Agent installation requires Linux" >&2; exit 1; }
    [[ $(id -u) -eq 0 ]] || { echo "Run as root or set DESTDIR" >&2; exit 1; }
    [[ -n $APP_USER ]] || {
        echo "Cannot determine the login user. Run with sudo or set APP_USER to a non-root user." >&2
        exit 1
    }
    [[ $APP_USER != root ]] || { echo "APP_USER must not be root" >&2; exit 1; }
    id -u "$APP_USER" >/dev/null 2>&1 || { echo "APP_USER does not exist: $APP_USER" >&2; exit 1; }
    APP_GROUP=${APP_GROUP:-$(id -gn "$APP_USER")}
    [[ $APP_GROUP =~ ^[a-z_][a-z0-9_-]*$ ]] || { echo "Invalid APP_GROUP: $APP_GROUP" >&2; exit 2; }
    require_commands nohup runuser chown ps tail cut sleep
    [[ -x $JAVA_BIN ]] || {
        echo "JDK 8 was not found at $JAVA_BIN. Set JAVA_BIN to the JDK 8 java executable." >&2
        exit 1
    }
    JAVAC_BIN="$(dirname "$JAVA_BIN")/javac"
    [[ -x $JAVAC_BIN ]] || { echo "A full JDK 8 is required; javac was not found at $JAVAC_BIN" >&2; exit 1; }
    JAVA_MAJOR=$(java_major)
    [[ $JAVA_MAJOR == 8 ]] || {
        echo "JDK 8 is required; current Java major version is ${JAVA_MAJOR:-unknown}" >&2
        exit 1
    }
fi

target() { printf '%s%s' "$DESTDIR" "$1"; }
APP_PATH=$(target "$APP_DIR")
CONFIG_PATH=$(target "$CONFIG_DIR")
DATA_PATH=$(target "$DATA_DIR")
CONFIG_FILE="$CONFIG_DIR/agent.json"
CONFIG_PATH_FILE=$(target "$CONFIG_FILE")
CONTROL_PATH="$APP_PATH/logmonitor-agent.sh"
LEGACY_SERVICE_PATH=$(target "$LEGACY_SERVICE_DIR/logmonitor-agent.service")
WAS_RUNNING=false
LEGACY_UNIT_CHANGED=false

if [[ -z $DESTDIR ]]; then
    if command -v systemctl >/dev/null 2>&1; then
        if systemctl is-active --quiet logmonitor-agent; then
            [[ -f $CONFIG_PATH_FILE ]] || { echo "Cannot migrate running Agent: missing $CONFIG_FILE" >&2; exit 1; }
            preflight_agent_for_user
            WAS_RUNNING=true
            echo "Stopping legacy systemd service logmonitor-agent"
            systemctl stop logmonitor-agent
        fi
        if systemctl is-enabled --quiet logmonitor-agent 2>/dev/null; then
            systemctl disable logmonitor-agent
            LEGACY_UNIT_CHANGED=true
        fi
    fi
    if [[ -x $CONTROL_PATH ]] && runuser -u "$APP_USER" -- "$CONTROL_PATH" status >/dev/null 2>&1; then
        runuser -u "$APP_USER" -- "$JAVA_BIN" -jar "$SCRIPT_DIR/logmonitor-agent.jar" \
            check --config "$CONFIG_FILE" --data "$DATA_DIR"
        WAS_RUNNING=true
        runuser -u "$APP_USER" -- "$CONTROL_PATH" stop
    fi
fi

if [[ -f $LEGACY_SERVICE_PATH ]]; then rm -f "$LEGACY_SERVICE_PATH"; LEGACY_UNIT_CHANGED=true; fi
if [[ -z $DESTDIR && $LEGACY_UNIT_CHANGED == true ]] && command -v systemctl >/dev/null 2>&1; then
    systemctl daemon-reload || echo "Warning: systemd daemon-reload failed after legacy unit removal" >&2
fi

install -d -m 0755 "$APP_PATH"
install -d -m 0750 "$CONFIG_PATH" "$DATA_PATH"
install -m 0644 "$SCRIPT_DIR/logmonitor-agent.jar" "$APP_PATH/logmonitor-agent.jar"
printf '%s\n' "$VERSION" > "$APP_PATH/VERSION"

RENDERED=$(mktemp)
trap 'rm -f "$RENDERED"' EXIT
sed -e "s|__APP_USER__|$APP_USER|g" \
    -e "s|__CONFIG_DIR__|$CONFIG_DIR|g" \
    -e "s|__DATA_DIR__|$DATA_DIR|g" \
    -e "s|__JAVA_BIN__|$JAVA_BIN|g" \
    -e "s|__APP_DIR__|$APP_DIR|g" \
    "$SCRIPT_DIR/logmonitor-agent.sh.template" > "$RENDERED"
install -m 0755 "$RENDERED" "$CONTROL_PATH"

if [[ -z $DESTDIR ]]; then
    chown -R "$APP_USER:$APP_GROUP" "$CONFIG_PATH" "$DATA_PATH"
    if [[ $CONFIGURE_AGENT == true && ! -f $CONFIG_PATH_FILE ]]; then
        CONFIGURE_ARGS=(configure --config "$CONFIG_FILE")
        if [[ $ALLOW_HTTP == true ]]; then CONFIGURE_ARGS+=(--allow-http); fi
        runuser -u "$APP_USER" -- "$JAVA_BIN" -jar "$APP_DIR/logmonitor-agent.jar" "${CONFIGURE_ARGS[@]}"
    fi
    if [[ $START_PROCESS == true || $WAS_RUNNING == true ]]; then
        [[ -f $CONFIG_PATH_FILE ]] || { echo "Configure the Agent before starting it" >&2; exit 1; }
        runuser -u "$APP_USER" -- "$CONTROL_PATH" start
    fi
fi

echo "Installed LogMonitor Agent $VERSION"
echo "Control command: $APP_DIR/logmonitor-agent.sh {start|stop|restart|status|logs}"
if [[ ! -f $CONFIG_PATH_FILE ]]; then
    echo "Configure with: runuser -u $APP_USER -- $JAVA_BIN -jar $APP_DIR/logmonitor-agent.jar configure --config $CONFIG_FILE [--allow-http]"
fi
if [[ -z $DESTDIR && $START_PROCESS != true && $WAS_RUNNING != true ]]; then
    echo "Then start with: runuser -u $APP_USER -- $APP_DIR/logmonitor-agent.sh start"
fi
