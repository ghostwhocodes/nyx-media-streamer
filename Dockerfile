# ── Build stage ───────────────────────────────────────────────────────
FROM eclipse-temurin:25-jdk AS builder

WORKDIR /build
COPY . .

RUN chmod +x mvnw codex/mvnw.sh && ./codex/mvnw.sh -pl :app -am -DskipTests package

# ── Runtime stage ────────────────────────────────────────────────────
FROM eclipse-temurin:25-jre

LABEL org.opencontainers.image.licenses="AGPL-3.0-only" \
      org.opencontainers.image.vendor="Nos Doughty" \
      org.opencontainers.image.authors="Nos Doughty"

RUN apt-get update && apt-get install -y --no-install-recommends \
        ffmpeg wget \
    && rm -rf /var/lib/apt/lists/*

RUN useradd --create-home --shell /bin/bash nyx
RUN mkdir -p /home/nyx/data/db && chown -R nyx:nyx /home/nyx/data
USER nyx
WORKDIR /home/nyx

COPY --from=builder /build/modules/application/app/target/app ./app

ENV NYX_HOST=0.0.0.0
ENV NYX_PORT=8080
ENV NYX_MEDIA_ROOT=/media
ENV NYX_DATABASE_DIR=/home/nyx/data/db

EXPOSE 8080

HEALTHCHECK --interval=30s --timeout=5s --retries=3 \
    CMD wget -qO- http://localhost:8080/api/v1/health/live > /dev/null 2>&1 || exit 1

ENTRYPOINT ["./app/bin/app"]
