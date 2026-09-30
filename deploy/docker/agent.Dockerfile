ARG BASE_IMAGE=amazoncorretto:8-alpine@sha256:5199bfbed72dc0c1e2c12b5380aef645204b24203c1c24bb5ad34124a39c2df6
FROM ${BASE_IMAGE}
ARG VERSION
LABEL org.opencontainers.image.title="LogMonitor Agent" org.opencontainers.image.version="${VERSION}"
RUN addgroup -g 10001 logmonitor && adduser -D -u 10001 -G logmonitor logmonitor && mkdir -p /config /data && chown 10001:10001 /config /data
WORKDIR /app
COPY agent.jar /app/agent.jar
USER 10001:10001
ENTRYPOINT ["java", "-jar", "/app/agent.jar"]
CMD ["run", "--config", "/config/agent.json", "--data", "/data"]
