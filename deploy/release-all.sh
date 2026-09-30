#!/usr/bin/env bash
# Requires JDK 17, JDK 8, Node 20.19+/22.12+, Maven and Docker on the build host.
set -euo pipefail
ROOT=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)
: "${BACKEND_JAVA_HOME:?Set BACKEND_JAVA_HOME to JDK 17}"
: "${AGENT_JAVA_HOME:?Set AGENT_JAVA_HOME to JDK 8}"
JAVA_HOME="$BACKEND_JAVA_HOME" PATH="$BACKEND_JAVA_HOME/bin:$PATH" "$ROOT/backend/deploy/release.sh"
JAVA_HOME="$AGENT_JAVA_HOME" PATH="$AGENT_JAVA_HOME/bin:$PATH" "$ROOT/agent/deploy/release.sh"
"$ROOT/frontend/deploy/release.sh"
"$ROOT/deploy/docker/release.sh"
