#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
FRONTEND_DIR=$(cd -- "$SCRIPT_DIR/.." && pwd)
REPOSITORY_ROOT=$(cd -- "$FRONTEND_DIR/.." && pwd)
OUTPUT_DIR=${OUTPUT_DIR:-$REPOSITORY_ROOT/release}
VERSION=${VERSION:-}
SKIP_TESTS=${SKIP_TESTS:-false}

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

command -v node >/dev/null || { echo "Node.js is required" >&2; exit 1; }
command -v npm >/dev/null || { echo "npm is required" >&2; exit 1; }
command -v tar >/dev/null || { echo "tar is required" >&2; exit 1; }

NODE_MAJOR=$(node -p "process.versions.node.split('.')[0]")
NODE_MINOR=$(node -p "process.versions.node.split('.')[1]")
if ! { [[ $NODE_MAJOR == 20 && $NODE_MINOR -ge 19 ]] || [[ $NODE_MAJOR == 22 && $NODE_MINOR -ge 12 ]] || [[ $NODE_MAJOR -gt 22 ]]; }; then
    echo "Node.js 20.19+, 22.12+, or a newer LTS release is required" >&2
    exit 1
fi

PROJECT_VERSION=$(node -p "require('$FRONTEND_DIR/package.json').version")
if [[ -z $VERSION ]]; then VERSION=$PROJECT_VERSION; fi
[[ $VERSION =~ ^[0-9A-Za-z][0-9A-Za-z._-]*$ ]] || { echo "Invalid version: $VERSION" >&2; exit 2; }
[[ $VERSION == "$PROJECT_VERSION" ]] || { echo "Release version must match package.json ($PROJECT_VERSION)" >&2; exit 2; }

cd "$FRONTEND_DIR"
npm ci
if [[ $SKIP_TESTS != true ]]; then npm test; fi
npm run build
[[ -f dist/index.html ]] || { echo "Frontend build did not produce dist/index.html" >&2; exit 1; }

mkdir -p "$OUTPUT_DIR"
OUTPUT_DIR=$(cd -- "$OUTPUT_DIR" && pwd)
PACKAGE_NAME="logmonitor-frontend-$VERSION"
STAGE_ROOT=$(mktemp -d)
trap 'rm -rf "$STAGE_ROOT"' EXIT
PACKAGE_DIR="$STAGE_ROOT/$PACKAGE_NAME"
mkdir -p "$PACKAGE_DIR"
cp -R dist "$PACKAGE_DIR/dist"
install -m 0755 "$SCRIPT_DIR/install.sh" "$PACKAGE_DIR/install.sh"
install -m 0644 "$SCRIPT_DIR/nginx.conf.template" "$PACKAGE_DIR/nginx.conf.template"
printf '%s\n' "$VERSION" > "$PACKAGE_DIR/VERSION"

ARCHIVE="$OUTPUT_DIR/$PACKAGE_NAME.tar.gz"
tar -C "$STAGE_ROOT" -czf "$ARCHIVE" "$PACKAGE_NAME"
if command -v sha256sum >/dev/null; then
    (cd "$OUTPUT_DIR" && sha256sum "$(basename "$ARCHIVE")") > "$ARCHIVE.sha256"
else
    (cd "$OUTPUT_DIR" && shasum -a 256 "$(basename "$ARCHIVE")") > "$ARCHIVE.sha256"
fi
echo "Created $ARCHIVE"
