# Build from repository root: docker build -f admin-frontend/Dockerfile .
ARG DOCKER_REGISTRY=docker.1ms.run/
FROM ${DOCKER_REGISTRY}node:22-alpine AS build

WORKDIR /build
COPY admin-frontend/package.json admin-frontend/package-lock.json ./
RUN npm config set registry https://registry.npmmirror.com && npm ci

COPY admin-frontend/ ./
RUN npm config set registry https://registry.npmmirror.com && npm run build

# ── Runtime ───────────────────────────────────────────────────────
FROM ${DOCKER_REGISTRY}node:22-alpine

WORKDIR /app
COPY admin-frontend/package.json admin-frontend/package-lock.json ./
RUN npm config set registry https://registry.npmmirror.com && npm ci --omit=dev

# Pre-built .next lives outside /app so bind-mount (../admin-frontend:/app)
# cannot shadow it.  The entrypoint copies it into /app/.next at startup.
COPY --from=build /build/.next /prebuilt/.next

COPY deploy/docker/entrypoint-admin.sh /entrypoint.sh
RUN chmod +x /entrypoint.sh

EXPOSE 5174
ENTRYPOINT ["/entrypoint.sh"]
