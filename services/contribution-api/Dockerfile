# Contribution ingestion service — Task 3.2.
#
# Build from the repository root, after `mvn -pl services/contribution-api package`:
#   docker build -f services/contribution-api/Dockerfile \
#     -t fondaria/contribution-api:dev services/contribution-api
#
# Run with a read-only root filesystem. /tmp must be a writable tmpfs: Tomcat
# needs a work directory, and application.yaml points server.tomcat.basedir
# there. Without the tmpfs the service will not start.
#   docker run --read-only --tmpfs /tmp:rw,noexec,nosuid,size=64m \
#     -e TIGERBEETLE_ADDRESSES=tigerbeetle:3000 fondaria/contribution-api:dev
#
# The equivalent Kubernetes securityContext and emptyDir arrive with Task 12.1.

# Distroless: a JRE and its libraries, no shell, no package manager. Pinned by
# digest because the :nonroot tag is rebuilt in place and is not immutable.
FROM gcr.io/distroless/java21-debian12:nonroot@sha256:7e37784d94dccbf5ccb195c73b295f5ad00cd266512dfbac12eb9c3c28f8077d

# 65532:65532 is distroless' own `nonroot` user. Set numerically so a Kubernetes
# runAsNonRoot check can verify it without resolving /etc/passwd.
USER 65532:65532

# The runnable jar is the `-exec` classifier, not the module's main artifact:
# spring-boot-maven-plugin repackages into it so the plain jar stays usable as a
# dependency, which libs/e2e-tests needs (ADR-0016).
COPY --chown=65532:65532 target/contribution-api-0.1.0-SNAPSHOT-exec.jar /app/contribution-api.jar

# Task 11.2: the OTel Java agent, fetched to target/otel-javaagent.jar at build
# time by the pom's maven-dependency-plugin execution (no curl/wget here — this
# image is distroless, no shell). This is the one service with a Dockerfile at
# all today (BACKLOG.md's scope note); the agent's own auto-instrumentation
# (JDBC in particular) fills the one gap the framework-native tracing wired
# into every service's own code (Micrometer Tracing + the OTLP exporters,
# application.yaml's `management.otlp.*`) does not cover without an extra
# dependency. Everything else here (metrics, domain counters, the trace
# continuation across the CDC boundary) works identically with or without this
# agent — it is proven by TelemetryIntegrationTest, which runs the service
# without it.
#
# KNOWN GAP, not silently worked around: the agent's own Spring MVC
# auto-instrumentation is not disabled here, so an HTTP server span may be
# recorded twice (once by the agent, once by Spring Boot's own
# ServerHttpObservationFilter) specifically in this packaged image. Selectively
# disabling the overlapping instrumentation module needs the exact current
# `OTEL_INSTRUMENTATION_*_ENABLED` flag name verified against a running
# container, which this session could not do — left for the runbook/operator
# to confirm empirically, the same "confirmed by exec, not assumed" standard
# Task 11.1 held itself to for its own container health checks.
#
# WIDER THAN THAT ONE GAP, also unverified in this packaged image: the agent
# runs its own OTel SDK instance, entirely separate from the one Micrometer
# Tracing's bridge builds from this service's own Spring beans. Two SDK
# instances sharing one JVM is a documented, supported OTel agent mode
# ("byte-buddy" auto-instrumentation is designed to co-exist with a
# manually-instrumented app), but nothing in this session exercised it: every
# test that proves trace continuity (TelemetryIntegrationTest,
# CdcBoundaryTraceEndToEndTest) raises contribution-api as an in-JVM Spring
# bean via PlatformStack, which never runs the agent at all — so a span the
# agent instruments (JDBC in particular) nesting under the wrong trace, or a
# disconnected trace entirely, in this specific packaged image is a real,
# unverified possibility, not merely the narrower double-HTTP-span gap above.
# Confirming it needs a running container with a real request traced end to
# end against Tempo, which this session did not build; left for the
# runbook/operator alongside the gap above.
COPY --chown=65532:65532 target/otel-javaagent.jar /app/otel-javaagent.jar
ENV JAVA_TOOL_OPTIONS="-javaagent:/app/otel-javaagent.jar"
ENV OTEL_SERVICE_NAME="contribution-api"
# Port 4318, not 4317: the agent's default OTLP protocol is http/protobuf, which
# talks to the Collector's HTTP receiver port -- matching application.yaml's own
# management.otlp.tracing.transport: http, which points at the same port.
# Confirmed empirically: the agent itself warns on a protocol/port mismatch at
# start-up if 4317 (the gRPC port) is used here without also setting
# OTEL_EXPORTER_OTLP_PROTOCOL=grpc.
ENV OTEL_EXPORTER_OTLP_ENDPOINT="http://otel-collector:4318"
ENV OTEL_TRACES_EXPORTER="otlp"
# Metrics and logs are exported by this service's own code (Micrometer's OTLP
# registry, and Task 11.5's logging layer respectively) — the agent exporting
# either too would double every value at the collector.
ENV OTEL_METRICS_EXPORTER="none"
ENV OTEL_LOGS_EXPORTER="none"

# The base image's entrypoint is already `java`, so only arguments are given here.
CMD ["-XX:MaxRAMPercentage=75.0", "-jar", "/app/contribution-api.jar"]
