#!/bin/sh
set -e
# Replace the named volume contents with the current build so stale hashed
# assets or deleted public files cannot survive across deployments.
find /dist-out -mindepth 1 -maxdepth 1 -exec rm -rf {} \;
cp -a /prebuilt-dist/. /dist-out/

if [ "$APP_PRODUCTION_MODE" = "true" ]; then
  echo "user-web: pre-built assets copied to /dist-out, idling for nginx..."
  exec tail -f /dev/null
else
  # Local dev: install deps then start Vite dev server with HMR
  npm config set registry https://registry.npmmirror.com
  npm install
  exec npm run dev -- --host 0.0.0.0 --port 5173
fi
