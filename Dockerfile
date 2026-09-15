FROM eclipse-temurin:21-jdk AS build
WORKDIR /app
# Copy only the build definition first, then resolve dependencies in a layer of their own.
# This layer is cached and reused on every build where the build files are unchanged, so a
# plain code change no longer re-downloads the whole dependency graph. The BuildKit cache
# mount additionally persists ~/.gradle (dependency + build cache) across builds — Railway
# supports RUN --mount=type=cache — so even a build-file change stays fast.
COPY gradlew settings.gradle.kts build.gradle.kts ./
COPY gradle gradle
RUN --mount=type=cache,target=/root/.gradle ./gradlew dependencies --no-daemon || true
COPY src src
RUN --mount=type=cache,target=/root/.gradle ./gradlew bootJar --no-daemon

FROM eclipse-temurin:21-jre
RUN apt-get update && apt-get install -y imagemagick libheif-dev && rm -rf /var/lib/apt/lists/*
WORKDIR /app
# Run as a non-root user to limit blast radius of any RCE/file-write bug.
RUN useradd --system --uid 10001 --home /app atlaso

# OpenTelemetry Java agent — ships Logback logs to Axiom over OTLP so logs are persisted
# and searchable (Railway's own log buffer is short-lived). Pinned for reproducibility.
ADD --chown=atlaso:atlaso https://github.com/open-telemetry/opentelemetry-java-instrumentation/releases/download/v2.31.1/opentelemetry-javaagent.jar /app/otel-agent.jar
COPY --from=build --chown=atlaso:atlaso /app/build/libs/*.jar app.jar
USER atlaso

# Logs-only, lean: disable all auto-instrumentation except the Logback→OTLP bridge, and
# turn off trace/metric export. Non-secret defaults live here; the Axiom credentials come
# from the AXIOM_TOKEN / AXIOM_DATASET env vars set in Railway.
ENV OTEL_SERVICE_NAME=atlaso-backend \
    OTEL_EXPORTER_OTLP_ENDPOINT=https://api.axiom.co \
    OTEL_EXPORTER_OTLP_PROTOCOL=http/protobuf \
    OTEL_LOGS_EXPORTER=otlp \
    OTEL_TRACES_EXPORTER=none \
    OTEL_METRICS_EXPORTER=none \
    OTEL_INSTRUMENTATION_COMMON_DEFAULT_ENABLED=false \
    OTEL_INSTRUMENTATION_LOGBACK_APPENDER_ENABLED=true \
    OTEL_INSTRUMENTATION_LOGBACK_APPENDER_EXPERIMENTAL_LOG_ATTRIBUTES=true

# Attach the agent (and compose the Axiom OTLP auth header) only when AXIOM_TOKEN is set,
# so local/unconfigured runs stay clean. Heap is sized to the container (see PDF-export fix).
ENTRYPOINT ["sh", "-c", "if [ -n \"$AXIOM_TOKEN\" ]; then AGENT=-javaagent:/app/otel-agent.jar; export OTEL_EXPORTER_OTLP_HEADERS=\"Authorization=Bearer $AXIOM_TOKEN,X-Axiom-Dataset=$AXIOM_DATASET\"; fi; exec java $AGENT -XX:MaxRAMPercentage=75.0 -jar /app/app.jar"]
