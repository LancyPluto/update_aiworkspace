# Optional Nginx and RabbitMQ local test

The default development flow still uses direct ports:

- User web: `http://localhost:5173`
- Admin: `http://localhost:5174`
- Backend: `http://localhost:8080`

For pre-release gateway testing, start the optional Nginx overlay:

```powershell
docker compose -f deploy/docker-compose.yml -f deploy/docker-compose.nginx.yml up -d
```

Then test:

- User web through Nginx: `http://localhost:8088`
- Admin through Nginx: `http://localhost:8088/admin/`
- API through Nginx: `http://localhost:8088/api/v1/health` if exposed by backend routes

Notes:

- `/api/internal/` is blocked by Nginx and must stay internal-only.
- SSE endpoints require `proxy_buffering off` and long proxy timeouts.
- RabbitMQ management UI is available at `http://localhost:15672` in local dev.
- Real secrets must not be committed. Use `.env.example` as a template only.
