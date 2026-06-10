# Tool Cover Media Configuration Design

## Goal

Let each AI tool's own admin configuration drive the card media shown on the
user-facing tool center, video tools page, and image tools page. Admin users
should be able to configure a real image, GIF, or video preview for a tool, and
the user side should render that media without needing a separate placement or
campaign system.

This design intentionally uses the existing tool configuration domain. A
separate page-placement or recommendation-slot system is out of scope for this
iteration.

## Current Context

- The user-facing `/tool`, `/video`, and `/image` pages share
  `user-web/src/pages/ToolCenter/Page.vue`.
- Tool cards are produced by
  `user-web/src/adapters/toolPresentationAdapter.ts` and rendered by
  `user-web/src/components/workspace/WorkspaceToolCard.vue`.
- The backend public tool list already returns `coverUrl` from
  `ai_tools.cover_url` through `/api/v1/tools`.
- The admin tool page already supports editing `coverUrl` and uploading tool
  presentation assets through `/api/admin/v1/tools/cover-upload`.
- Uploaded files are stored under the generated media directory and exposed as
  `/generated/tool-covers/<filename>`.

## Architecture

The authoritative display media field remains `ai_tools.cover_url`.

Admin configuration flow:

1. An admin edits a tool in the tools management page.
2. The admin either enters an external media URL or uploads an image, GIF, or
   video file.
3. The upload endpoint returns a public `/generated/tool-covers/...` URL.
4. Saving the tool persists that URL into `ai_tools.cover_url`.

User display flow:

1. The user page calls `/api/v1/tools` or `/api/v1/tools/search`.
2. `ToolCenter/Page.vue` maps each `ToolSummary` into a workspace card.
3. `WorkspaceToolCard.vue` renders `coverUrl` as either image media or video
   media.
4. If no media is configured, or the configured media cannot render, the card
   falls back to the existing bundled workspace image.

## User-Facing Behavior

Tool cards should support these configured media types:

- Images: JPG, JPEG, PNG, WebP.
- Animated image previews: GIF.
- Video previews: MP4, WebM, MOV, M4V.

Video cards should render as muted, looping, inline preview media. They should
not require user interaction to show the visual preview, and they should not
show browser video controls inside the compact card.

The same configured media should appear consistently wherever the shared
workspace tool card is used, including:

- `/tool`
- `/video`
- `/image`
- authenticated home sections that reuse `WorkspaceToolCard`

## Admin Behavior

The existing admin tool form remains the editing surface. It should continue to
offer:

- Direct URL entry for `coverUrl`.
- Drag-and-drop or file picker upload for image, GIF, and video previews.
- A local preview that renders video URLs as video and image URLs as images.

No new database table, public API, or page-specific placement editor is needed
for this iteration.

## Error Handling

User card fallback behavior:

- Empty `coverUrl`: use the existing category-aware fallback image.
- Image load failure: switch once to the default tool cover image.
- Video load failure: switch once to the default tool cover image.
- Unknown extension: treat the URL as an image so external image CDNs without
  strict extensions still have a chance to render.

Admin upload behavior remains handled by the backend:

- Reject empty files.
- Reject files over the existing size limit.
- Reject unsupported extensions and content types.

Generated media availability must be handled operationally by keeping
`ai_tools.cover_url` aligned with files present in the backend generated-media
volume.

## Testing

Frontend adapter tests should cover:

- A configured image URL maps into the card media URL.
- A configured video URL is classified as video card media.
- A missing `coverUrl` still uses the existing fallback.

Component or build verification should cover:

- `WorkspaceToolCard.vue` renders a video element for video URLs.
- `WorkspaceToolCard.vue` renders an image element for image URLs.
- Card layout stays stable for both media types.

Backend coverage already verifies:

- Admin can save an image or video `coverUrl`.
- Public `/api/v1/tools` returns the saved `coverUrl`.
- Admin upload returns a `/generated/tool-covers/...` URL.

Manual smoke checks should include:

1. Save a JPG or PNG tool cover in the admin page and confirm it appears in
   `/tool`.
2. Save an MP4 tool preview in the admin page and confirm it appears in
   `/video`.
3. Configure a missing or invalid URL and confirm the user card falls back
   without a broken visual.

## Deployment Notes

The runtime already proxies `/generated/*` through Vite in local development
and through Nginx in deployment. For imported config bundles that reference
existing `/generated/tool-covers/...` files, the corresponding media files must
also be present in `data/generated-media/tool-covers` or copied into the
backend container volume.

The existing `deploy/scripts/upload_tool_covers.py` script can be used to sync
local generated tool cover files to the remote generated-media volume.
