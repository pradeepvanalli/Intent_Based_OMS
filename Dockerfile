# syntax=docker/dockerfile:1.7
# Intent OMS image: React UI + Spring Boot backend in one jar, on a non-root JRE 21 runtime.
# At the repository root, so `docker build .` (or `make try` / `scripts/try.sh`) just works with no -f flag:
#   docker build -t intent-oms .
# No Java or Node is needed on the host: the UI is built in a Node stage, the backend in a Maven stage. BuildKit cache
# mounts keep npm and Maven downloads between builds, so a rebuild after a code change takes a fraction of the first.

# ---- build UI ----
FROM node:22-alpine AS ui
WORKDIR /ui
COPY ui/package*.json ./
RUN --mount=type=cache,target=/root/.npm npm ci --no-audit --no-fund
COPY ui/ .
RUN npm run build

# ---- build backend ----
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /src
COPY backend/ backend/
COPY --from=ui /ui/dist backend/oms-app/src/main/resources/static/
RUN --mount=type=cache,target=/root/.m2 mvn -q -B -ntp -f backend/pom.xml -DskipTests package

# ---- runtime ----
# Non-root, read-only-root-filesystem friendly: the only writable location the JVM needs is /tmp (mount a tmpfs there;
# see docker-compose.yml `oms` and deploy/k8s/oms.yaml). Config is baked read-only under /config.
FROM eclipse-temurin:21-jre-alpine
ARG VERSION=dev
ARG SOURCE_URL=https://github.com/pradeepvanalli/Intent_Based_OMS
LABEL org.opencontainers.image.title="Intent OMS" \
      org.opencontainers.image.description="Open-source order management system with typed, explainable AI decisions" \
      org.opencontainers.image.source="${SOURCE_URL}" \
      org.opencontainers.image.version="${VERSION}" \
      org.opencontainers.image.licenses="Apache-2.0"
WORKDIR /app
RUN addgroup -S oms && adduser -S oms -G oms && mkdir -p /tmp && chmod 1777 /tmp
COPY --from=build --chown=oms:oms /src/backend/oms-app/target/intent-oms.jar app.jar
COPY --chown=oms:oms config/ /config/
ENV OMS_CONFIG_DIR=/config \
    JAVA_OPTS="-XX:MaxRAMPercentage=75 -XX:+UseZGC -XX:+ZGenerational -Djava.io.tmpdir=/tmp -Djava.security.egd=file:/dev/./urandom"
USER oms
EXPOSE 8080
VOLUME ["/tmp"]
HEALTHCHECK --interval=15s --timeout=3s --retries=20 CMD wget -qO- http://localhost:8080/actuator/health/readiness || exit 1
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar app.jar"]
