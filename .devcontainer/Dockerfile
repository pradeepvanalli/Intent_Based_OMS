# Base image for the Intent OMS dev container. JDK 21 (Temurin), Maven, Node 22 and Docker come from the features in
# devcontainer.json; this layer only adds the small OS tools the repository scripts use.
FROM mcr.microsoft.com/devcontainers/base:ubuntu-24.04

RUN apt-get update \
 && DEBIAN_FRONTEND=noninteractive apt-get install -y --no-install-recommends \
      python3 lsof jq postgresql-client curl ca-certificates unzip \
 && apt-get clean && rm -rf /var/lib/apt/lists/*

# k6 (optional: `make load`). Download is best effort so an offline build still succeeds.
RUN set -e; ARCH="$(dpkg --print-architecture)"; V=0.54.0; \
    curl -fsSL "https://github.com/grafana/k6/releases/download/v${V}/k6-v${V}-linux-${ARCH}.tar.gz" | tar -xz -C /tmp \
 && mv /tmp/k6-v${V}-linux-${ARCH}/k6 /usr/local/bin/k6 && rm -rf /tmp/k6-* || echo "k6 download skipped"
