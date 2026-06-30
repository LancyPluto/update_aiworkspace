#!/bin/sh
set -e
# Copy pre-built .next into /app (bind mount may have shadowed it).
# Only needed when image was built with source changes; on pure config
# restarts the copy is a no-op since .next already matches.
if [ "$APP_PRODUCTION_MODE" = "true" ]; then
  cp -a /prebuilt/.next /app/.next 2>/dev/null || true
  NODE_ENV=production exec npm run start -- -H 0.0.0.0 -p 5174
else
  # Local dev: install full deps (including devDependencies) then start dev server
  npm config set registry https://registry.npmmirror.com
  npm install
  CHOKIDAR_USEPOLLING=true exec npm run dev:docker
fi
