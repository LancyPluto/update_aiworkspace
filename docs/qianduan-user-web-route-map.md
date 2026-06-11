# Qianduan User-Web Route Map

This document records the route and deployment contract for the migrated
qianduan-style user frontend. The authoritative runtime sources are
`user-web/src/router/index.ts`, `user-web/src/app/routes/publicRoutes.ts`,
`user-web/src/app/routes/protectedRoutes.ts`, `user-web/vite.config.ts`,
`deploy/nginx/default.conf`, and `scripts/migration_cutover_smoke.py`; this
file is the deployment and regression checklist view of those contracts.

## Entry Contract

| Route | Auth | Runtime behavior |
| --- | --- | --- |
| `/` | Public | Website login/landing page. Logged-in users are redirected to `/home` unless a valid `redirect` query points elsewhere. |
| `/login` | Public | Compatibility alias for the same login page. Logged-in users are redirected with the same post-login rule as `/`. |
| `/home` | Protected | Migrated qianduan-style authenticated home. This is the default successful-login destination. |

Post-login redirects must stay internal paths beginning with `/`. The login
guard intentionally normalizes `/`, `/home`, `/login`, and invalid redirects to
`/home` to avoid login loops.

## Primary User Routes

Runtime route definitions are split by access boundary: public routes live in
`user-web/src/app/routes/publicRoutes.ts`, protected routes live in
`user-web/src/app/routes/protectedRoutes.ts`, and `router/index.ts` only
combines them with the auth guard.

| Route | Auth | Current page/component | Notes |
| --- | --- | --- | --- |
| `/create` | Protected | `CreatorWorkspace/Page.vue` | Primary migrated creator workspace. Uses backend tool detail, `DynamicForm`, `creatorAdapter`, and `taskApi.createTask`. |
| `/video` | Public view | `VideoTools/Page.vue` | Backend-driven video-capable tool discovery. Task submission still routes through protected creation flows. |
| `/image` | Public view | `ImageTools/Page.vue` | Backend-driven image-capable tool discovery. Task submission still routes through protected creation flows. |
| `/tool` | Public | `ToolCenter/Page.vue` | Backend-driven tool center with categories, search, pagination, and cover fallbacks. |
| `/tools/:id` | Public | `ToolDetail/Page.vue` | Existing tool detail contract preserved. PPT tools route to the PPT workspace; ordinary tools enter `/create?tool=<toolCode>`. |
| `/tools/:id/use` | Protected | `ToolUse/Page.vue` | Legacy dynamic tool-use surface retained for compatibility. |
| `/tasks` | Protected | `MyTasks/Page.vue` | Preserves task filtering, cancel, delete, regenerate, and cancelled-task display. |
| `/tasks/:taskId/status` | Protected | `TaskStatus/Page.vue` | Preserves SSE status stream with polling fallback. |
| `/tasks/:taskId/result` | Protected | `TaskResult/Page.vue` | Preserves generated text/image/audio/video/report result rendering through existing result utilities. |
| `/assets` | Protected | `MaterialLibrary/Page.vue` | Migrated asset/library route. Preserves preview, download, replay, delete, publish, and unpublish behavior. |
| `/billing` | Protected | `Billing/Page.vue` | Preserves credit account, package loading, order creation, and payment state. |
| `/profile` | Protected | `Profile/Page.vue` | Preserves profile, avatar, and community settings behavior. |
| `/community` | Public | `CommunityDiscover/Page.vue` | Existing community discovery route retained. |
| `/community/posts/:postId` | Public | `CommunityPost/Page.vue` | Existing post detail, like/favorite, same-style, and collection behavior retained. |
| `/community/inspirations` | Protected | `InspirationCollections/Page.vue` | Existing inspiration collection behavior retained. |
| `/u/:userId` | Public | `PublicProfile/Page.vue` | Existing public profile behavior retained. |
| `/agent` | Protected | `AgentHome/Page.vue` | Existing Agent session/message/run-event/file behavior retained inside the migrated shell. |
| `/agents` | Public | `AgentPlaceholder/Page.vue` | Public placeholder/compatibility entry. |
| `/chat/:toolId` | Public | `Chat/Page.vue` | Legacy chat route retained so previous shared links and file-upload chat flows stay reachable. |
| `/tools/banana_ppt_generator/workspace` | Protected | `PptWorkspace/Page.vue` | Existing PPT workspace entry retained. |
| `/tools/banana_ppt_generator/workspace/:bindingId` | Protected | `PptWorkspace/Editor.vue` | Existing PPT project editor retained. |

Internal business actions must target the migrated routes directly. Asset
replay, same-style creation, Agent asset recommendations, and old ToolList
category cards should generate `/create` URLs instead of relying on the
`/dashboard` compatibility redirect.

The old visual-only `Dashboard/Page.vue` and `ToolList/Page.vue` surfaces have
been removed after their route references were replaced by `/create` and
`ToolCenter/Page.vue`; keep the compatibility route names only as redirects and
navigation identities.

## Compatibility Redirects

These redirects are intentionally kept for externally shared URLs and old user
habits. Do not remove them until analytics and stakeholder review confirm they
are no longer needed.

| Old route | Redirect target |
| --- | --- |
| `/dashboard` | `/create` with original query preserved |
| `/marketplace` | `/tool` with original query preserved |
| `/tools` | `/tool` with original query preserved |
| `/library` | `/assets` with original query preserved |
| `/pricing` | `/billing` with original query preserved |

## Auth Guard Expectations

Routes whose `meta.requiresAuth` is not explicitly `false` are protected. A
logged-out user opening a protected route must be sent to:

```text
/?redirect=<original-full-path>
```

Public routes must remain reachable without login. Protected routes must remain
reachable after login and should continue to use the migrated `WorkspaceShell`
where applicable.

## Proxy And Deployment Contract

The frontend must remain the `user-web` application and deployment root.

Local Vite development:

| Path | Target |
| --- | --- |
| `/api/*` | `VITE_DEV_PROXY_TARGET`, default `http://127.0.0.1:8080` |
| `/generated/*` | `VITE_DEV_PROXY_TARGET`, default `http://127.0.0.1:8080` |

The local Vite proxy strips browser `Origin` before forwarding `/api` and
`/generated` requests so temporary dev-server ports do not trigger backend CORS
rejections during browser QA.

Nginx deployment:

| Path | Target |
| --- | --- |
| `/` and user routes | `user-web:5173` |
| `/api/*` | `backend:8080` |
| `/generated/*` | `backend:8080` |
| `/api/internal/*` | `404` from public Nginx |
| `/admin/*` and `/_next/*` | `admin-frontend:5174` |

Production cutover must re-run the route, API, and generated-media smoke checks
against the target domain after DNS, TLS, and host configuration are applied.
The baseline smoke includes a negative probe for `/api/internal/health` and
requires the public Nginx path to return `404`.
Final approval runs of `scripts/migration_cutover_smoke.py` must include
`--require-all-gates`, which fails fast unless real image, real video,
media-publish, and full PPT generation/export gates are all enabled. In this
strict mode the base URL must be an HTTPS non-local staging or production
domain. A JSON result with `"ok": true` but `"approvalReady": false` is only a
baseline/rehearsal pass; final approval requires `"approvalReady": true`.

## Static Data Boundary

The migrated pages may use static qianduan assets as presentation fallbacks, but
production business content must come from the existing backend APIs:

- Tool lists and tool cards: `/api/v1/tools` and `/api/v1/tools/{toolCode}`.
- Task creation and status: `/api/v1/tasks*`.
- Generated media: `/generated/*` served by the backend.
- Assets/material library: task detail/list data plus generated result
  resources and community publish state.
- Billing, profile, community, Agent, and PPT: their existing API modules.

Do not reintroduce `qianduan/src/services/studioApi.ts` or static workspace
tool data as production business sources.

## Cutover Regression Checklist

Run these checks before declaring the user frontend ready for production
cutover:

1. `/` renders the login/landing entry; `user1`-style password login redirects
   to `/home` in local/staging test data.
2. Public `/tool`, `/video`, `/image`, `/community`, `/community/posts/:postId`,
   and `/u/:userId` are reachable without login.
3. Protected `/home`, `/create`, `/tasks`, `/assets`, `/billing`, `/profile`,
   `/agent`, and PPT workspace routes redirect to `/?redirect=...` when logged
   out and open after login.
4. Compatibility redirects for `/dashboard`, `/marketplace`, `/tools`,
   `/library`, and `/pricing` resolve to their target routes.
5. `/api/health`, `/api/v1/tools`, authenticated `/api/v1/users/me`, and
   generated `/generated/*` media work through the deployment host.
6. Image/video creation, task status, task result, and asset preview/download/
   publish/unpublish are repeated with the real production media providers, not
   only local mock tools.
7. PPT outline, descriptions, images, PPTX/PDF export, editable PPTX export,
   and the exported file downloads are repeated after banana-slides provider settings
   are configured for the real environment.
8. Nginx still blocks public `/api/internal/*`.
9. The final smoke command uses `--require-all-gates`, points at an HTTPS
   non-local staging/production domain, and does not use `--allow-mock-tools`.
