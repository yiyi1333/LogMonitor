#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
AGENT_DIR=$(cd -- "$SCRIPT_DIR/.." && pwd)
REPOSITORY_ROOT=$(cd -- "$AGENT_DIR/.." && pwd)
OUTPUT_DIR=${OUTPUT_DIR:-$REPOSITORY_ROOT/release}
VERSION=${VERSION:-}
SKIP_TESTS=${SKIP_TESTS:-false}

usage() {
    echo "Usage: $0 [--version VERSION] [--output-dir DIR] [--skip-tests]"
}

java_major() {
    local value
    value=$(java -version 2>&1 | sed -n '1s/.*version "\([^"]*\)".*/\1/p')
    if [[ $value == 1.8.* ]]; then echo 8; else echo "${value%%.*}"; fi
}

while (($#)); do
    case "$1" in
        --version) VERSION=${2:?missing version}; shift 2 ;;
        --output-dir) OUTPUT_DIR=${2:?missing output directory}; shift 2 ;;
        --skip-tests) SKIP_TESTS=true; shift ;;
        -h|--help) usage; exit 0 ;;
        *) echo "Unknown option: $1" >&2; usage >&2; exit 2 ;;
    esac
done

command -v java >/dev/null || { echo "JDK 8 is required" >&2; exit 1; }
command -v mvn >/dev/null || { echo "Maven 3.9+ is required" >&2; exit 1; }
command -v tar >/dev/null || { echo "tar is required" >&2; exit 1; }
JAVA_MAJOR=$(java_major)
[[ $JAVA_MAJOR == 8 ]] || { echo "JDK 8 is required; current major version is ${JAVA_MAJOR:-unknown}" >&2; exit 1; }

PROJECT_VERSION=$(sed -n '/<artifactId>log-monitor-agent<\/artifactId>/,/<properties>/s/.*<version>\([^<]*\)<\/version>.*/\1/p' "$AGENT_DIR/pom.xml" | head -n 1)
if [[ -z $VERSION ]]; then VERSION=$PROJECT_VERSION; fi
[[ $VERSION =~ ^[0-9A-Za-z][0-9A-Za-z._-]*$ ]] || { echo "Invalid version: $VERSION" >&2; exit 2; }
[[ $VERSION == "$PROJECT_VERSION" ]] || { echo "Release version must match pom.xml ($PROJECT_VERSION)" >&2; exit 2; }

cd "$AGENT_DIR"
if [[ $SKIP_TESTS == true ]]; then
    mvn clean package -DskipTests
else
    mvn clean package
fi
JAR="$AGENT_DIR/target/logmonitor-agent.jar"
[[ -f $JAR ]] || { echo "Agent build did not produce $JAR" >&2; exit 1; }
java -jar "$JAR" --help | grep "LogMonitor Agent $VERSION" >/dev/null || {
    echo "Agent runtime version does not match pom.xml ($VERSION)" >&2
    exit 1
}

mkdir -p "$OUTPUT_DIR"
OUTPUT_DIR=$(cd -- "$OUTPUT_DIR" && pwd)
PACKAGE_NAME="logmonitor-agent-$VERSION"
STAGE_ROOT=$(mktemp -d)
trap 'rm -rf "$STAGE_ROOT"' EXIT
PACKAGE_DIR="$STAGE_ROOT/$PACKAGE_NAME"
mkdir -p "$PACKAGE_DIR"
install -m 0644 "$JAR" "$PACKAGE_DIR/logmonitor-agent.jar"
install -m 0755 "$SCRIPT_DIR/install.sh" "$PACKAGE_DIR/install.sh"
install -m 0644 "$SCRIPT_DIR/logmonitor-agent.sh.template" "$PACKAGE_DIR/logmonitor-agent.sh.template"
printf '%s\n' "$VERSION" > "$PACKAGE_DIR/VERSION"

ARCHIVE="$OUTPUT_DIR/$PACKAGE_NAME.tar.gz"
tar -C "$STAGE_ROOT" -czf "$ARCHIVE" "$PACKAGE_NAME"
if command -v sha256sum >/dev/null; then
    (cd "$OUTPUT_DIR" && sha256sum "$(basename "$ARCHIVE")") > "$ARCHIVE.sha256"
else
    (cd "$OUTPUT_DIR" && shasum -a 256 "$(basename "$ARCHIVE")") > "$ARCHIVE.sha256"
fi
echo "Created $ARCHIVE"
