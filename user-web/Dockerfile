# Build from repository root: docker build -f user-web/Dockerfile .
ARG DOCKER_REGISTRY=docker.1ms.run/
FROM ${DOCKER_REGISTRY}node:22-alpine AS build

WORKDIR /build
COPY user-web/package.json user-web/package-lock.json ./
RUN npm config set registry https://registry.npmmirror.com && npm ci

COPY user-web/ ./
RUN npm config set registry https://registry.npmmirror.com && npm run build

# ── Runtime ───────────────────────────────────────────────────────
FROM ${DOCKER_REGISTRY}node:22-alpine

WORKDIR /app

# Pre-built dist lives outside /app so bind-mount cannot shadow it.
# The entrypoint copies it to /dist-out (named volume → nginx serves it).
COPY --from=build /build/dist /prebuilt-dist

COPY deploy/docker/entrypoint-userweb.sh /entrypoint.sh
RUN chmod +x /entrypoint.sh

EXPOSE 5173
ENTRYPOINT ["/entrypoint.sh"]
