#!/bin/sh
set -e
# Copy pre-built dist to the named volume that nginx serves.
cp -a /prebuilt-dist/. /dist-out/ 2>/dev/null || true

if [ "$APP_PRODUCTION_MODE" = "true" ]; then
  echo "user-web: pre-built assets copied to /dist-out, idling for nginx..."
  exec tail -f /dev/null
else
  # Local dev: install deps then start Vite dev server with HMR
  npm config set registry https://registry.npmmirror.com
  npm install
  exec npm run dev -- --host 0.0.0.0 --port 5173
fi
