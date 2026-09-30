#!/usr/bin/env bash
set -euo pipefail
SCRIPT_DIR=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
ROOT=$(cd -- "$SCRIPT_DIR/../.." && pwd)
OUTPUT_DIR=${OUTPUT_DIR:-$ROOT/release}
PLATFORM=${PLATFORM:-linux/amd64}
VERSION=$(sed -n '/<artifactId>log-monitor-backend<\/artifactId>/,/<name>/s/.*<version>\([^<]*\)<\/version>.*/\1/p' "$ROOT/backend/pom.xml" | head -1)
case "$PLATFORM" in linux/amd64|linux/arm64) ;; *) echo 'PLATFORM must be linux/amd64 or linux/arm64' >&2; exit 2;; esac
[[ $VERSION =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]] || exit 2
mkdir -p "$OUTPUT_DIR"
OUTPUT_DIR=$(cd -- "$OUTPUT_DIR" && pwd)
PACKAGE="logmonitor-docker-$VERSION-${PLATFORM//\//-}"
STAGE=$(mktemp -d)
trap 'rm -rf "$STAGE"' EXIT
DEST="$STAGE/$PACKAGE"
mkdir -p "$DEST"
for component in backend frontend agent; do
    archive="logmonitor-$component-$VERSION.tar.gz"
    [[ -f $OUTPUT_DIR/$archive && -f $OUTPUT_DIR/$archive.sha256 ]] || { echo "First generate $archive with tests" >&2; exit 1; }
    if command -v sha256sum >/dev/null; then
        (cd "$OUTPUT_DIR" && sha256sum -c "$archive.sha256")
    else
        (cd "$OUTPUT_DIR" && shasum -a 256 -c "$archive.sha256")
    fi
    tar -xzf "$OUTPUT_DIR/$archive" -C "$STAGE"
    [[ $(cat "$STAGE/logmonitor-$component-$VERSION/VERSION") == "$VERSION" ]] || exit 1
done
cp "$SCRIPT_DIR/"*.Dockerfile "$SCRIPT_DIR/"*.yaml "$SCRIPT_DIR/nginx.conf" "$SCRIPT_DIR/.dockerignore" "$SCRIPT_DIR/.env.example" "$SCRIPT_DIR/README.md" "$DEST/"
cp "$STAGE/logmonitor-backend-$VERSION/logmonitor-backend.jar" "$DEST/backend.jar"
cp "$STAGE/logmonitor-agent-$VERSION/logmonitor-agent.jar" "$DEST/agent.jar"
cp -R "$STAGE/logmonitor-frontend-$VERSION/dist" "$DEST/frontend"
cp -R "$STAGE/logmonitor-backend-$VERSION/mysql" "$DEST/mysql"
mkdir "$DEST/config"
cp "$STAGE/logmonitor-backend-$VERSION/application.example.yml" "$DEST/config/application.yml"
cp "$STAGE/logmonitor-backend-$VERSION/application-prod.example.yml" "$DEST/config/application-prod.yml"
# Only safe config templates and built payloads enter the image build context.
for component in backend frontend agent; do
    docker build --platform "$PLATFORM" --build-arg "VERSION=$VERSION" \
        -f "$DEST/$component.Dockerfile" -t "logmonitor/$component:$VERSION" "$DEST"
done
docker pull --platform "$PLATFORM" mysql:8.4
docker image inspect "logmonitor/backend:$VERSION" "logmonitor/frontend:$VERSION" "logmonitor/agent:$VERSION" mysql:8.4 \
    --format '{{.RepoTags}} {{.Id}} {{.Os}}/{{.Architecture}} {{json .RepoDigests}}' > "$DEST/IMAGES.txt"
printf '%s\n' "$VERSION" > "$DEST/VERSION"
printf '%s\n' "$PLATFORM" > "$DEST/PLATFORM"
docker save -o "$DEST/images.tar" "logmonitor/backend:$VERSION" "logmonitor/frontend:$VERSION" "logmonitor/agent:$VERSION" mysql:8.4
ARCHIVE="$OUTPUT_DIR/$PACKAGE.tar.gz"
tar -C "$STAGE" -czf "$ARCHIVE" "$PACKAGE"
if command -v sha256sum >/dev/null; then
    (cd "$OUTPUT_DIR" && sha256sum "$PACKAGE.tar.gz") > "$ARCHIVE.sha256"
else
    (cd "$OUTPUT_DIR" && shasum -a 256 "$PACKAGE.tar.gz") > "$ARCHIVE.sha256"
fi
echo "Created $ARCHIVE"
