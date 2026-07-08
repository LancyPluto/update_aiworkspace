# Build from repository root: docker build -f admin-frontend/Dockerfile .
ARG DOCKER_REGISTRY=docker.1ms.run/
FROM ${DOCKER_REGISTRY}node:22-alpine AS build

WORKDIR /build
ARG NEXT_PUBLIC_ADMIN_BASE_PATH=/admin
ARG NEXT_PUBLIC_API_PROXY_TARGET=http://backend:8080
ARG NEXT_PUBLIC_API_BASE_URL=
ENV NEXT_PUBLIC_ADMIN_BASE_PATH=${NEXT_PUBLIC_ADMIN_BASE_PATH}
ENV NEXT_PUBLIC_API_PROXY_TARGET=${NEXT_PUBLIC_API_PROXY_TARGET}
ENV NEXT_PUBLIC_API_BASE_URL=${NEXT_PUBLIC_API_BASE_URL}
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
