#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
VERSION=$(tr -d '[:space:]' < "$SCRIPT_DIR/VERSION")
DESTDIR=${DESTDIR:-}
FRONTEND_BASE=${FRONTEND_BASE:-/opt/logmonitor/frontend}
NGINX_CONF_DIR=${NGINX_CONF_DIR:-/etc/nginx/conf.d}
LISTEN_ADDRESS=${LISTEN_ADDRESS:-127.0.0.1}
LISTEN_PORT=${LISTEN_PORT:-8081}
SERVER_NAME=${SERVER_NAME:-_}
BACKEND_URL=${BACKEND_URL:-http://127.0.0.1:8080}
INSTALL_NGINX_CONFIG=${INSTALL_NGINX_CONFIG:-true}
RELOAD_NGINX=${RELOAD_NGINX:-true}

[[ -f $SCRIPT_DIR/dist/index.html ]] || { echo "Missing frontend dist/index.html" >&2; exit 1; }
[[ $VERSION =~ ^[0-9A-Za-z][0-9A-Za-z._-]*$ ]] || { echo "Invalid VERSION" >&2; exit 1; }
[[ -z $DESTDIR || $DESTDIR == /* ]] || { echo "DESTDIR must be absolute" >&2; exit 2; }
[[ $LISTEN_ADDRESS =~ ^[0-9A-Fa-f:.]+$ ]] || { echo "Invalid LISTEN_ADDRESS" >&2; exit 2; }
[[ $LISTEN_PORT =~ ^[0-9]+$ ]] && ((LISTEN_PORT >= 1 && LISTEN_PORT <= 65535)) || { echo "Invalid LISTEN_PORT" >&2; exit 2; }
[[ $SERVER_NAME =~ ^[A-Za-z0-9._*-]+$ ]] || { echo "Invalid SERVER_NAME" >&2; exit 2; }
[[ $BACKEND_URL =~ ^https?://[A-Za-z0-9._:-]+$ ]] || { echo "Invalid BACKEND_URL" >&2; exit 2; }
if [[ -z $DESTDIR && $(id -u) -ne 0 ]]; then echo "Run as root or set DESTDIR" >&2; exit 1; fi

target() { printf '%s%s' "$DESTDIR" "$1"; }
BASE_PATH=$(target "$FRONTEND_BASE")
RELEASE_PATH="$BASE_PATH/releases/$VERSION"
CURRENT_PATH="$BASE_PATH/current"
install -d -m 0755 "$RELEASE_PATH"
cp -R "$SCRIPT_DIR/dist/." "$RELEASE_PATH/"
find "$RELEASE_PATH" -type d -exec chmod 0755 {} +
find "$RELEASE_PATH" -type f -exec chmod 0644 {} +
if [[ -e $CURRENT_PATH && ! -L $CURRENT_PATH ]]; then
    echo "$CURRENT_PATH exists and is not a symbolic link" >&2
    exit 1
fi
NEXT_LINK="$BASE_PATH/.current.$$"
ln -s "releases/$VERSION" "$NEXT_LINK"
if ! mv -Tf "$NEXT_LINK" "$CURRENT_PATH" 2>/dev/null && ! mv -f "$NEXT_LINK" "$CURRENT_PATH"; then
    rm -f "$NEXT_LINK"
    exit 1
fi

if [[ $INSTALL_NGINX_CONFIG == true ]]; then
    if [[ -z $DESTDIR ]]; then command -v nginx >/dev/null || { echo "nginx is required" >&2; exit 1; }; fi
    CONF_PATH=$(target "$NGINX_CONF_DIR/logmonitor-frontend.conf")
    install -d -m 0755 "$(dirname "$CONF_PATH")"
    RENDERED=$(mktemp)
    BACKUP=$(mktemp)
    trap 'rm -f "$RENDERED" "$BACKUP"' EXIT
    sed -e "s|__LISTEN_ADDRESS__|$LISTEN_ADDRESS|g" \
        -e "s|__LISTEN_PORT__|$LISTEN_PORT|g" \
        -e "s|__SERVER_NAME__|$SERVER_NAME|g" \
        -e "s|__BACKEND_URL__|$BACKEND_URL|g" \
        -e "s|__FRONTEND_ROOT__|$FRONTEND_BASE/current|g" \
        "$SCRIPT_DIR/nginx.conf.template" > "$RENDERED"
    HAD_CONFIG=false
    if [[ -f $CONF_PATH ]]; then cp "$CONF_PATH" "$BACKUP"; HAD_CONFIG=true; fi
    install -m 0644 "$RENDERED" "$CONF_PATH"
    if [[ -z $DESTDIR ]]; then
        if ! nginx -t; then
            if [[ $HAD_CONFIG == true ]]; then install -m 0644 "$BACKUP" "$CONF_PATH"; else rm -f "$CONF_PATH"; fi
            echo "nginx configuration validation failed; previous configuration restored" >&2
            exit 1
        fi
        if [[ $RELOAD_NGINX == true ]]; then
            if command -v systemctl >/dev/null && systemctl is-active --quiet nginx; then systemctl reload nginx
            else nginx -s reload || echo "nginx is not running; configuration is installed but was not reloaded" >&2
            fi
        fi
    fi
fi

echo "Installed LogMonitor frontend $VERSION at $FRONTEND_BASE/current"
