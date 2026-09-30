#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
BACKEND_DIR=$(cd -- "$SCRIPT_DIR/.." && pwd)
REPOSITORY_ROOT=$(cd -- "$BACKEND_DIR/.." && pwd)
OUTPUT_DIR=${OUTPUT_DIR:-$REPOSITORY_ROOT/release}
VERSION=${VERSION:-}
SKIP_TESTS=${SKIP_TESTS:-false}
FIRST_MANUAL_PRODUCTION_MIGRATION=14

usage() {
    echo "Usage: $0 [--version VERSION] [--output-dir DIR] [--skip-tests]"
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

command -v java >/dev/null || { echo "JDK 17 is required" >&2; exit 1; }
command -v mvn >/dev/null || { echo "Maven 3.9+ is required" >&2; exit 1; }
command -v tar >/dev/null || { echo "tar is required" >&2; exit 1; }
[[ -f $BACKEND_DIR/config/application.example.yml ]] || { echo "Missing application.example.yml" >&2; exit 1; }
[[ -f $BACKEND_DIR/config/application-prod.example.yml ]] || { echo "Missing application-prod.example.yml" >&2; exit 1; }
JAVA_MAJOR=$(java -version 2>&1 | sed -n '1s/.*version "\([0-9]*\).*/\1/p')
[[ $JAVA_MAJOR == 17 ]] || { echo "JDK 17 is required; current major version is ${JAVA_MAJOR:-unknown}" >&2; exit 1; }

ARTIFACT_VERSION=$(sed -n '/<artifactId>log-monitor-backend<\/artifactId>/,/<name>/s/.*<version>\([^<]*\)<\/version>.*/\1/p' "$BACKEND_DIR/pom.xml" | head -n 1)
if [[ -z $VERSION ]]; then VERSION=$ARTIFACT_VERSION; fi
[[ $VERSION =~ ^[0-9A-Za-z][0-9A-Za-z._-]*$ ]] || { echo "Invalid version: $VERSION" >&2; exit 2; }
[[ $VERSION == "$ARTIFACT_VERSION" ]] || { echo "Release version must match pom.xml ($ARTIFACT_VERSION)" >&2; exit 2; }

cd "$BACKEND_DIR"
if [[ $SKIP_TESTS == true ]]; then
    mvn -s settings-ci.xml clean package -DskipTests
else
    mvn -s settings-ci.xml clean package
fi
JAR="$BACKEND_DIR/target/log-monitor-backend-$ARTIFACT_VERSION.jar"
[[ -f $JAR ]] || { echo "Backend build did not produce $JAR" >&2; exit 1; }
if jar tf "$JAR" | grep -E 'BOOT-INF/classes/(static/|index\.html)' >/dev/null; then
    echo "Backend JAR unexpectedly contains frontend assets" >&2
    exit 1
fi
if jar tf "$JAR" | grep -E 'BOOT-INF/classes/application[^/]*\.(yml|yaml|properties)$' >/dev/null; then
    echo "Backend JAR unexpectedly contains an application configuration" >&2
    exit 1
fi

mkdir -p "$OUTPUT_DIR"
OUTPUT_DIR=$(cd -- "$OUTPUT_DIR" && pwd)
PACKAGE_NAME="logmonitor-backend-$VERSION"
STAGE_ROOT=$(mktemp -d)
trap 'rm -rf "$STAGE_ROOT"' EXIT
PACKAGE_DIR="$STAGE_ROOT/$PACKAGE_NAME"
mkdir -p "$PACKAGE_DIR"
install -m 0644 "$JAR" "$PACKAGE_DIR/logmonitor-backend.jar"
install -m 0755 "$SCRIPT_DIR/start.sh" "$PACKAGE_DIR/start.sh"
install -m 0755 "$SCRIPT_DIR/shutdown.sh" "$PACKAGE_DIR/shutdown.sh"
install -m 0644 "$SCRIPT_DIR/backend.env.example" "$PACKAGE_DIR/backend.env.example"
install -m 0644 "$BACKEND_DIR/config/application.example.yml" "$PACKAGE_DIR/application.example.yml"
install -m 0644 "$BACKEND_DIR/config/application-prod.example.yml" "$PACKAGE_DIR/application-prod.example.yml"
install -m 0644 "$SCRIPT_DIR/logmonitor-backend.sh.template" "$PACKAGE_DIR/logmonitor-backend.sh.template"
cp -R "$REPOSITORY_ROOT/deploy/mysql" "$PACKAGE_DIR/mysql"
mkdir -p "$PACKAGE_DIR/mysql/migrations"
shopt -s nullglob
for migration in "$BACKEND_DIR"/src/main/resources/db/migration/V*.sql \
        "$BACKEND_DIR"/src/main/resources/db/mysql-migration/V*.sql; do
    migration_name=$(basename "$migration")
    migration_version=${migration_name#V}
    migration_version=${migration_version%%__*}
    if ((10#$migration_version >= FIRST_MANUAL_PRODUCTION_MIGRATION)); then
        install -m 0644 "$migration" "$PACKAGE_DIR/mysql/migrations/$migration_name"
    fi
done
printf '%s\n' "$VERSION" > "$PACKAGE_DIR/VERSION"

ARCHIVE="$OUTPUT_DIR/$PACKAGE_NAME.tar.gz"
tar -C "$STAGE_ROOT" -czf "$ARCHIVE" "$PACKAGE_NAME"
if command -v sha256sum >/dev/null; then
    (cd "$OUTPUT_DIR" && sha256sum "$(basename "$ARCHIVE")") > "$ARCHIVE.sha256"
else
    (cd "$OUTPUT_DIR" && shasum -a 256 "$(basename "$ARCHIVE")") > "$ARCHIVE.sha256"
fi
echo "Created $ARCHIVE"
