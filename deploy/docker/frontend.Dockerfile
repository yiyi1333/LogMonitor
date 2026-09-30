ARG BASE_IMAGE=nginx:stable-alpine@sha256:0985e772fb9f729e6fa0980da05fca5d9c468e870eed43071545afa9d2e27d94
FROM ${BASE_IMAGE}
ARG VERSION
LABEL org.opencontainers.image.title="LogMonitor Frontend" org.opencontainers.image.version="${VERSION}"
COPY frontend/ /usr/share/nginx/html/
COPY nginx.conf /etc/nginx/conf.d/default.conf
EXPOSE 8080
HEALTHCHECK --interval=10s --timeout=3s --retries=3 CMD wget -q -O /dev/null http://127.0.0.1:8080/ || exit 1
