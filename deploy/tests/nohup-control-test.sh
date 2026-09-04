#!/usr/bin/env bash
set -euo pipefail

REPOSITORY_ROOT=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../.." && pwd)
TEST_ROOT=$(mktemp -d)
if [[ ${KEEP_TEST_ROOT:-false} == true ]]; then
    echo "Keeping test directory: $TEST_ROOT"
else
    trap 'rm -rf "$TEST_ROOT"' EXIT
fi
mkdir -p "$TEST_ROOT/bin" "$TEST_ROOT/backend/data" "$TEST_ROOT/agent/data"

cat > "$TEST_ROOT/bin/java" <<'SCRIPT'
#!/usr/bin/env bash
set -euo pipefail
if [[ ${1:-} == -version ]]; then
    echo "openjdk version \"${FAKE_JAVA_VERSION:-17.0.1}\"" >&2
    exit 0
fi
for argument in "$@"; do
    if [[ $argument == check ]]; then
        if [[ ${FAKE_CHECK_FAIL:-false} == true ]]; then
            echo "simulated Agent preflight failure" >&2
            exit 1
        fi
        echo "simulated Agent preflight success"
        exit 0
    fi
done
if [[ ${FAKE_IGNORE_TERM:-false} == true ]]; then trap '' TERM; else trap 'exit 0' TERM; fi
while true; do sleep 1; done
SCRIPT

cat > "$TEST_ROOT/bin/curl" <<'SCRIPT'
#!/usr/bin/env bash
if [[ ${FAKE_CURL_FAIL:-false} == true ]]; then exit 22; fi
exit 0
SCRIPT
chmod 0755 "$TEST_ROOT/bin/java" "$TEST_ROOT/bin/curl"

render_backend() {
    local directory=$TEST_ROOT/backend
    mkdir -p "$directory/config"
    touch "$directory/logmonitor-backend.jar"
    touch "$directory/config/application.yml" "$directory/config/application-prod.yml"
    cat > "$directory/backend.env" <<'ENV'
SERVER_PORT=8080
DB_PASSWORD=test-password
LLM_CONFIG_MASTER_KEY=test-master-key
ENV
    sed -e "s|__APP_USER__|$(id -un)|g" \
        -e "s|__JAVA_BIN__|$TEST_ROOT/bin/java|g" \
        -e "s|__APP_DIR__|$directory|g" \
        -e "s|__ENV_FILE__|$directory/backend.env|g" \
        -e "s|__CONFIG_DIR__|$directory/config|g" \
        -e "s|__DATA_DIR__|$directory/data|g" \
        "$REPOSITORY_ROOT/backend/deploy/logmonitor-backend.sh.template" > "$directory/control.sh"
    chmod 0755 "$directory/control.sh"
}

render_agent() {
    local directory=$TEST_ROOT/agent
    touch "$directory/logmonitor-agent.jar" "$directory/agent.json"
    sed -e "s|__APP_USER__|$(id -un)|g" \
        -e "s|__JAVA_BIN__|$TEST_ROOT/bin/java|g" \
        -e "s|__APP_DIR__|$directory|g" \
        -e "s|__CONFIG_DIR__|$directory|g" \
        -e "s|__DATA_DIR__|$directory/data|g" \
        "$REPOSITORY_ROOT/agent/deploy/logmonitor-agent.sh.template" > "$directory/control.sh"
    chmod 0755 "$directory/control.sh"
}

assert_running() {
    local control=$1 expected=$2 output
    output=$($control status)
    [[ $output == *"$expected"* ]] || { echo "Unexpected status: $output" >&2; exit 1; }
}

assert_stopped() {
    local control=$1 output
    if output=$($control status 2>&1); then
        echo "Expected stopped status" >&2
        exit 1
    fi
    [[ $output == *"stopped"* ]] || { echo "Unexpected stopped status: $output" >&2; exit 1; }
}

render_backend
render_agent
export PATH="$TEST_ROOT/bin:$PATH"

export FAKE_JAVA_VERSION=17.0.1
"$TEST_ROOT/backend/control.sh" start
assert_running "$TEST_ROOT/backend/control.sh" "backend is running"
"$TEST_ROOT/backend/control.sh" start | grep -q "already running"
"$TEST_ROOT/backend/control.sh" logs >/dev/null
"$TEST_ROOT/backend/control.sh" stop
assert_stopped "$TEST_ROOT/backend/control.sh"

export FAKE_IGNORE_TERM=true
"$TEST_ROOT/backend/control.sh" start
STOP_TIMEOUT_SECONDS=1 "$TEST_ROOT/backend/control.sh" stop
assert_stopped "$TEST_ROOT/backend/control.sh"
unset FAKE_IGNORE_TERM

printf '%s\n' "$$" > "$TEST_ROOT/backend/data/logmonitor-backend.pid"
assert_stopped "$TEST_ROOT/backend/control.sh"
[[ ! -f $TEST_ROOT/backend/data/logmonitor-backend.pid ]]

export FAKE_CURL_FAIL=true
if STARTUP_TIMEOUT_SECONDS=1 "$TEST_ROOT/backend/control.sh" start >/dev/null 2>&1; then
    echo "Expected backend health timeout" >&2
    exit 1
fi
assert_stopped "$TEST_ROOT/backend/control.sh"
unset FAKE_CURL_FAIL

export FAKE_JAVA_VERSION=1.8.0_492
"$TEST_ROOT/agent/control.sh" start
assert_running "$TEST_ROOT/agent/control.sh" "Agent is running"
export FAKE_CHECK_FAIL=true
if "$TEST_ROOT/agent/control.sh" restart >/dev/null 2>&1; then
    echo "Expected Agent restart preflight failure" >&2
    exit 1
fi
assert_running "$TEST_ROOT/agent/control.sh" "Agent is running"
unset FAKE_CHECK_FAIL
"$TEST_ROOT/agent/control.sh" restart
assert_running "$TEST_ROOT/agent/control.sh" "Agent is running"
"$TEST_ROOT/agent/control.sh" stop
assert_stopped "$TEST_ROOT/agent/control.sh"

export FAKE_IGNORE_TERM=true
"$TEST_ROOT/agent/control.sh" start
STOP_TIMEOUT_SECONDS=1 "$TEST_ROOT/agent/control.sh" stop
assert_stopped "$TEST_ROOT/agent/control.sh"
unset FAKE_IGNORE_TERM

printf '%s\n' "$$" > "$TEST_ROOT/agent/data/logmonitor-agent.pid"
assert_stopped "$TEST_ROOT/agent/control.sh"
[[ ! -f $TEST_ROOT/agent/data/logmonitor-agent.pid ]]

echo "nohup control script tests: OK"
