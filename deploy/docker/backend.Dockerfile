ARG BASE_IMAGE=amazoncorretto:17-alpine@sha256:80d268d89c10861f33792c7845bbd7c8494678a600f031988e135fe27eae3b48
FROM ${BASE_IMAGE}
ARG VERSION
LABEL org.opencontainers.image.title="LogMonitor Backend" org.opencontainers.image.version="${VERSION}"
RUN addgroup -g 10001 logmonitor && adduser -D -u 10001 -G logmonitor logmonitor
WORKDIR /app
COPY backend.jar /app/backend.jar
COPY config/ /app/config/
USER 10001:10001
EXPOSE 8080
HEALTHCHECK --interval=10s --timeout=5s --start-period=60s --retries=6 CMD wget -q -O /dev/null http://127.0.0.1:8080/api/health || exit 1
ENTRYPOINT ["java", "-jar", "/app/backend.jar", "--spring.config.location=file:/app/config/application.yml,file:/app/config/application-prod.yml"]
