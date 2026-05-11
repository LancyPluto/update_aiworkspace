# Phase 1.3 Next.js Agent Chat Frontend Implementation Plan

> Superseded: Phase 1 implementation should now follow `docs/superpowers/plans/2026-05-10-cloud-universal-agent-mvp-framework-dev.md`. This file is retained only as historical detail. The current decision is to build Agent UI inside existing `user-web`, not a new `agent-web`.

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build a standalone Next.js Agent Chat web app that gives users a ChatGPT-style interface for creating sessions, sending natural-language messages, seeing Agent run progress, viewing tool call cards, and recovering history from Spring Boot APIs.

**Architecture:** The frontend is a new `agent-web/` app using Next.js, React, TypeScript, Tailwind, shadcn/ui, and Vercel AI SDK where useful. It talks only to the Spring Boot backend public APIs from Phase 1.1 and never calls the FastAPI Agent Service directly.

**Tech Stack:** Next.js, React, TypeScript, Tailwind CSS, shadcn/ui, lucide-react, Vercel AI SDK, Spring Boot public Agent APIs, SSE or polling run events.

---

## 1. Scope

Phase 1.3 builds only the Agent user-facing frontend.

It includes:

- New `agent-web/` app.
- Login flow using existing Spring Boot user auth.
- Token storage for user access token.
- Agent chat shell.
- Session sidebar.
- Message list.
- Composer.
- Run status timeline.
- Tool call cards.
- Agent API client.
- Run event polling first, SSE-compatible event model later.
- Basic loading, empty, error, unauthorized states.
- Responsive desktop and mobile layouts.
- Docker Compose service design.

It does not include:

- Spring Boot API implementation.
- FastAPI Agent Service implementation.
- Admin management pages.
- File upload.
- Knowledge base UI.
- RAG citations.
- Native WebSocket.
- Full Vercel AI SDK server route proxy.
- Complex markdown editor.

---

## 2. Dependencies

This frontend depends on Phase 1.1 public APIs:

```text
POST /api/v1/agent/sessions
GET  /api/v1/agent/sessions
GET  /api/v1/agent/sessions/{sessionId}
GET  /api/v1/agent/sessions/{sessionId}/messages
POST /api/v1/agent/sessions/{sessionId}/messages
GET  /api/v1/agent/runs/{runId}
POST /api/v1/agent/runs/{runId}/cancel
GET  /api/v1/agent/runs/{runId}/events
```

This frontend consumes event types emitted through Phase 1.2:

```text
run.started
intent.detected
tool.selected
tool.started
tool.finished
message.delta
message.completed
run.completed
run.failed
```

The frontend must not call:

```text
FastAPI Agent Service
/api/internal/**
```

---

## 3. App Strategy

Create a new app:

```text
agent-web/
```

Reasons:

- The target frontend stack is Next.js + React, while current `user-web` is Vue.
- Agent chat has a different interaction model from the existing form-based tool market.
- Keeping it separate avoids breaking the current user-facing app.
- It can later become the unified user portal after the Agent experience is proven.

---

## 4. Product Experience

First screen after login:

```text
left sidebar:
  new chat
  session list

main area:
  current conversation
  agent progress events
  tool call cards
  composer
```

User flow:

```text
1. User logs in.
2. User lands on /agent.
3. App creates or opens an active session.
4. User sends a natural-language message.
5. UI immediately shows the user message.
6. Backend returns runId.
7. UI polls or streams run events.
8. UI displays Agent progress.
9. UI appends assistant answer deltas.
10. UI marks run success or failure.
11. Refreshing page reloads persisted messages and events.
```

---

## 5. Directory Structure

Create:

```text
agent-web/
  app/
    layout.tsx
    page.tsx
    globals.css
    login/
      page.tsx
    agent/
      page.tsx
      [sessionId]/
        page.tsx
  components/
    agent/
      agent-shell.tsx
      composer.tsx
      empty-state.tsx
      message-item.tsx
      message-list.tsx
      run-event-list.tsx
      run-status.tsx
      session-sidebar.tsx
      tool-call-card.tsx
    auth/
      login-form.tsx
      auth-guard.tsx
    layout/
      app-header.tsx
      mobile-nav.tsx
    ui/
  lib/
    api/
      agent.ts
      auth.ts
      client.ts
    hooks/
      use-agent-chat.ts
      use-run-events.ts
      use-sessions.ts
    storage/
      session.ts
    types/
      agent.ts
      api.ts
      auth.ts
    utils.ts
  public/
  components.json
  next.config.mjs
  package.json
  postcss.config.mjs
  tsconfig.json
```

---

## 6. Package Setup

`package.json` scripts:

```json
{
  "scripts": {
    "dev": "next dev -p 5175",
    "dev:docker": "next dev -H 0.0.0.0 -p 5175",
    "build": "next build",
    "start": "next start -p 5175",
    "lint": "next lint",
    "typecheck": "tsc --noEmit"
  }
}
```

Dependencies:

```text
next
react
react-dom
typescript
tailwindcss
@tailwindcss/postcss
lucide-react
class-variance-authority
clsx
tailwind-merge
@radix-ui/react-dialog
@radix-ui/react-dropdown-menu
@radix-ui/react-scroll-area
@radix-ui/react-separator
@radix-ui/react-tooltip
@radix-ui/react-avatar
@ai-sdk/react
ai
```

shadcn/ui components to add first:

```text
button
input
textarea
scroll-area
separator
avatar
tooltip
dialog
dropdown-menu
badge
skeleton
```

---

## 7. Environment Variables

Create:

```text
agent-web/.env.example
```

Content:

```text
NEXT_PUBLIC_API_BASE_URL=http://localhost:8080
NEXT_PUBLIC_AGENT_APP_NAME=AI Tool Market Agent
```

`NEXT_PUBLIC_API_BASE_URL` points to Spring Boot, not FastAPI.

---

## 8. Routing

Routes:

```text
/login
/agent
/agent/[sessionId]
```

Behavior:

- `/` redirects to `/agent`.
- `/agent` opens the most recent session or shows empty state.
- `/agent/[sessionId]` opens a specific session.
- unauthenticated users redirect to `/login`.

---

## 9. Authentication

### 9.1 API

Use existing Spring Boot auth endpoints:

```text
POST /api/v1/auth/login
GET  /api/v1/users/me
```

### 9.2 Token storage

Use localStorage keys distinct from admin:

```text
agent_access_token
agent_user_profile
```

Do not reuse admin keys:

```text
admin_access_token
admin_user_profile
```

### 9.3 AuthGuard

`components/auth/auth-guard.tsx`:

- if no token, redirect to `/login`.
- if token exists, render children.
- API 401 clears token and redirects to login.

---

## 10. API Client

`lib/api/client.ts`

Responsibilities:

- Build URL from `NEXT_PUBLIC_API_BASE_URL`.
- Attach Bearer token.
- Parse Spring Boot `ApiResponse<T>`.
- Throw typed `ApiError`.
- Clear session on 401.

Spring Boot response format:

```ts
export interface ApiResponse<T> {
  code: string
  message?: string
  data: T
  requestId?: string
}
```

Request helper:

```ts
export async function request<T>(
  path: string,
  options?: {
    method?: "GET" | "POST" | "PUT" | "DELETE" | "PATCH"
    query?: Record<string, string | number | boolean | undefined | null>
    body?: unknown
    signal?: AbortSignal
  },
): Promise<T>
```

---

## 11. Type Definitions

`lib/types/agent.ts`

```ts
export type AgentSessionStatus = "ACTIVE" | "ARCHIVED" | "DELETED"

export interface AgentSession {
  id: number
  title: string
  status: AgentSessionStatus
  createdAt: string
  updatedAt: string
}

export type AgentMessageRole = "USER" | "ASSISTANT" | "SYSTEM" | "TOOL"

export interface AgentMessage {
  id: number
  sessionId: number
  role: AgentMessageRole
  contentText: string
  contentJson?: unknown
  runId?: number | null
  createdAt: string
}

export type AgentRunStatus =
  | "CREATED"
  | "RUNNING"
  | "WAITING_USER_CONFIRMATION"
  | "SUCCESS"
  | "FAILED"
  | "TIMEOUT"
  | "CANCELLED"

export interface AgentRun {
  id: number
  sessionId: number
  status: AgentRunStatus
  intent?: string | null
  modelProviderCode?: string | null
  modelName?: string | null
  estimatedCredits: number
  consumedCredits: number
  errorCode?: string | null
  errorMessage?: string | null
  startedAt?: string | null
  finishedAt?: string | null
  createdAt: string
  updatedAt: string
}

export type AgentRunEventType =
  | "run.started"
  | "intent.detected"
  | "tool.selected"
  | "tool.started"
  | "tool.finished"
  | "message.delta"
  | "message.completed"
  | "run.completed"
  | "run.failed"

export interface AgentRunEvent {
  id: number
  runId: number
  eventType: AgentRunEventType
  eventText?: string | null
  eventJson?: unknown
  createdAt: string
}
```

---

## 12. Agent API Wrapper

`lib/api/agent.ts`

Functions:

```ts
export async function createSession(title?: string): Promise<AgentSession>
export async function listSessions(query?: PageQuery): Promise<PageResponse<AgentSession>>
export async function getSession(sessionId: number): Promise<AgentSession>
export async function listMessages(sessionId: number, query?: PageQuery): Promise<PageResponse<AgentMessage>>
export async function sendMessage(sessionId: number, content: string, clientRequestId: string): Promise<CreateAgentMessageResponse>
export async function getRun(runId: number): Promise<AgentRun>
export async function cancelRun(runId: number): Promise<AgentRun>
export async function listRunEvents(runId: number, afterEventId?: number): Promise<PageResponse<AgentRunEvent>>
```

Types:

```ts
export interface CreateAgentMessageResponse {
  sessionId: number
  messageId: number
  runId: number
  runStatus: AgentRunStatus
}
```

---

## 13. State Hooks

### 13.1 useSessions

`lib/hooks/use-sessions.ts`

Responsibilities:

- Load session list.
- Create new session.
- Optimistically add new session.
- Track selected session.
- Expose loading and error states.

Return:

```ts
{
  sessions,
  loading,
  error,
  refresh,
  createNewSession
}
```

### 13.2 useRunEvents

`lib/hooks/use-run-events.ts`

First implementation uses polling:

```text
poll every 1000ms while run is active
stop when run.completed or run.failed appears
```

Inputs:

```ts
runId: number | null
enabled: boolean
```

Return:

```ts
{
  events,
  assistantDraft,
  running,
  error
}
```

Behavior:

- Track latest event id.
- Append only new events.
- Build `assistantDraft` by concatenating `message.delta`.
- Stop polling on terminal event.

SSE can replace polling in Phase 1.5 without changing component API.

### 13.3 useAgentChat

`lib/hooks/use-agent-chat.ts`

Responsibilities:

- Load messages for selected session.
- Send user message.
- Add optimistic user message.
- Start run event tracking.
- Append assistant response when complete.
- Refresh persisted messages after run completion.

Vercel AI SDK:

- Use `@ai-sdk/react` patterns where helpful for message state.
- Do not force the Vercel AI SDK protocol if Spring Boot APIs are not compatible yet.
- Keep internal hook shape compatible with future `useChat` replacement.

---

## 14. Component Design

### 14.1 AgentShell

`components/agent/agent-shell.tsx`

Layout:

```text
desktop:
  sidebar 280px
  main flexible

mobile:
  header
  session drawer
  main full width
```

Responsibilities:

- Load sessions.
- Select session from route param.
- Create session when none exists.
- Render sidebar and main chat.

### 14.2 SessionSidebar

`components/agent/session-sidebar.tsx`

Includes:

- New chat button.
- Session list.
- Active session highlight.
- Empty state.
- Loading skeleton.

Do not use large marketing-style cards. Keep it dense and utilitarian.

### 14.3 MessageList

`components/agent/message-list.tsx`

Responsibilities:

- Render chronological messages.
- Render assistant draft during active run.
- Keep scroll pinned to bottom when user is already near bottom.
- Preserve user scroll when reading older messages.

### 14.4 MessageItem

`components/agent/message-item.tsx`

Visuals:

- User messages aligned right or clearly distinguished.
- Assistant messages aligned left with avatar.
- System/tool messages shown as compact status rows.

Supports:

- plain text first.
- markdown rendering can be added in Phase 1.5 or later.

### 14.5 Composer

`components/agent/composer.tsx`

Controls:

- Multiline textarea.
- Send button with icon.
- Stop/cancel button during active run.
- Disabled state when no session or sending.

Keyboard:

```text
Enter sends
Shift+Enter inserts newline
```

Validation:

- trim empty messages.
- max length 8000.

### 14.6 RunEventList

`components/agent/run-event-list.tsx`

Displays current run process:

```text
正在理解需求
已识别：工具调用
已选择：AI 小红书文案生成器
正在生成
生成完成
```

Keep it compact. This is execution feedback, not a tutorial panel.

### 14.7 ToolCallCard

`components/agent/tool-call-card.tsx`

Displayed when events include:

```text
tool.selected
tool.started
tool.finished
```

Fields:

- tool name.
- status.
- short argument summary.
- result summary.
- error if failed.

### 14.8 EmptyState

`components/agent/empty-state.tsx`

Purpose:

- Show a quiet starting state with example prompts.
- Avoid landing-page hero treatment.

Example prompts:

```text
帮我写一篇小红书种草文案
帮我优化一个电商商品标题
帮我生成一条朋友圈推广文案
```

---

## 15. Event Handling

### 15.1 Event to UI mapping

```text
run.started:
  show running state

intent.detected:
  show intent label

tool.selected:
  create tool card

tool.started:
  mark tool card running

tool.finished:
  mark tool card success or failed

message.delta:
  append to assistantDraft

message.completed:
  mark assistant draft complete

run.completed:
  stop polling and refresh messages

run.failed:
  show error, stop polling
```

### 15.2 Terminal events

Terminal:

```text
run.completed
run.failed
```

Also stop polling if `getRun` returns:

```text
SUCCESS
FAILED
TIMEOUT
CANCELLED
```

---

## 16. Styling Guidelines

The Agent UI is an app, not a landing page.

Use:

- dense, calm, work-focused layout.
- compact sidebar.
- clear message rhythm.
- minimal borders.
- 8px or less card radius.
- icon buttons for compact controls.
- readable line length.
- stable composer height.

Avoid:

- oversized hero sections.
- decorative gradient backgrounds.
- nested cards.
- marketing copy inside the app.
- one-note purple/blue palette.
- text overlapping buttons.

Recommended visual hierarchy:

```text
sidebar: muted background
main: white or neutral background
messages: subtle contrast
tool cards: compact bordered panels
composer: fixed bottom input surface
```

---

## 17. Responsive Behavior

Desktop:

- Sidebar visible.
- Composer fixed at bottom of main pane.
- Message area scrolls.

Tablet:

- Sidebar collapsible.
- Chat remains primary.

Mobile:

- Sidebar hidden behind menu.
- Composer full width.
- Tool cards collapse to compact status rows.
- Text must wrap cleanly.

---

## 18. Error States

Handle:

```text
401 unauthorized:
  clear token and redirect login

network error:
  show retry message

send message failure:
  keep draft in composer or allow retry

run failed:
  show error block in conversation

event polling failure:
  show reconnecting state, retry with backoff

session not found:
  redirect to /agent and show toast/error
```

No toast system is required in first implementation if inline errors are clear.

---

## 19. Loading States

Use skeletons for:

- session list.
- message history.

Use inline spinner/status for:

- sending message.
- active run.
- polling events.

Do not let dynamic labels resize major layout areas.

---

## 20. Accessibility

Minimum requirements:

- Composer textarea has accessible label.
- Icon buttons have `aria-label`.
- Send button disabled state is clear.
- Keyboard submit works.
- Focus remains in composer after send.
- Color is not the only status signal.
- Tool cards expose text status.

---

## 21. Docker Compose Integration

Add service later in:

```text
deploy/docker-compose.yml
```

Service:

```yaml
  agent-web:
    image: node:22-alpine
    container_name: ai-supermarket-agent-web
    working_dir: /app
    restart: unless-stopped
    depends_on:
      - backend
    environment:
      NEXT_PUBLIC_API_BASE_URL: ${NEXT_PUBLIC_API_BASE_URL:-http://localhost:8080}
      CHOKIDAR_USEPOLLING: "true"
    ports:
      - "${AGENT_WEB_PORT:-5175}:5175"
    volumes:
      - ../agent-web:/app
    command: sh -c "npm install && npm run dev:docker"
```

---

## 22. Testing Strategy

### 22.1 Type checks

Run:

```powershell
cd agent-web
npm run typecheck
```

Expected:

```text
No TypeScript errors
```

### 22.2 Build

Run:

```powershell
cd agent-web
npm run build
```

Expected:

```text
Build succeeds
```

### 22.3 Manual browser verification

Use Browser plugin after dev server starts.

Viewports:

```text
desktop: 1440x900
mobile: 390x844
```

Check:

- login page renders.
- agent page renders.
- sidebar does not overlap messages.
- composer does not overlap content.
- long messages wrap.
- tool cards fit mobile.
- loading states are not layout-breaking.

### 22.4 API integration smoke

With backend running:

```text
login
create session
send message
poll events
show assistant draft
refresh page
messages persist
```

---

## 23. Implementation Tasks

### Task 1: Scaffold agent-web

**Files:**

- Create: `agent-web/package.json`
- Create: `agent-web/tsconfig.json`
- Create: `agent-web/next.config.mjs`
- Create: `agent-web/postcss.config.mjs`
- Create: `agent-web/app/layout.tsx`
- Create: `agent-web/app/globals.css`
- Create: `agent-web/components.json`

- [ ] **Step 1: Create Next.js app structure**

Create the app manually or with `create-next-app`, using App Router and TypeScript.

- [ ] **Step 2: Install dependencies**

Install dependencies from Section 6.

- [ ] **Step 3: Configure Tailwind and shadcn/ui**

Add base styles and component aliases.

- [ ] **Step 4: Run dev server**

```powershell
cd agent-web
npm run dev
```

Expected:

```text
App starts on http://localhost:5175
```

### Task 2: Add API client and auth storage

**Files:**

- Create: `agent-web/lib/api/client.ts`
- Create: `agent-web/lib/api/auth.ts`
- Create: `agent-web/lib/storage/session.ts`
- Create: `agent-web/lib/types/api.ts`
- Create: `agent-web/lib/types/auth.ts`

- [ ] **Step 1: Implement token storage**

Use `agent_access_token` and `agent_user_profile`.

- [ ] **Step 2: Implement request helper**

Parse `ApiResponse<T>` and handle 401.

- [ ] **Step 3: Implement auth API**

Add `login` and `fetchMe`.

- [ ] **Step 4: Typecheck**

```powershell
cd agent-web
npm run typecheck
```

Expected:

```text
No TypeScript errors
```

### Task 3: Add login page

**Files:**

- Create: `agent-web/app/login/page.tsx`
- Create: `agent-web/components/auth/login-form.tsx`
- Create: `agent-web/components/auth/auth-guard.tsx`

- [ ] **Step 1: Build login form**

Fields:

```text
username
password
```

- [ ] **Step 2: Submit login**

Call Spring Boot auth API and store token.

- [ ] **Step 3: Redirect after login**

Redirect to `/agent`.

- [ ] **Step 4: Add AuthGuard**

Protect `/agent` pages.

### Task 4: Add Agent API wrapper and types

**Files:**

- Create: `agent-web/lib/types/agent.ts`
- Create: `agent-web/lib/api/agent.ts`

- [ ] **Step 1: Add types from Section 11**

Define sessions, messages, runs, and events.

- [ ] **Step 2: Add API functions from Section 12**

Implement all public Agent API wrappers.

- [ ] **Step 3: Typecheck**

```powershell
cd agent-web
npm run typecheck
```

Expected:

```text
No TypeScript errors
```

### Task 5: Build session shell

**Files:**

- Create: `agent-web/app/page.tsx`
- Create: `agent-web/app/agent/page.tsx`
- Create: `agent-web/app/agent/[sessionId]/page.tsx`
- Create: `agent-web/components/agent/agent-shell.tsx`
- Create: `agent-web/components/agent/session-sidebar.tsx`
- Create: `agent-web/lib/hooks/use-sessions.ts`

- [ ] **Step 1: Implement route redirects**

`/` redirects to `/agent`.

- [ ] **Step 2: Implement session loading**

Load sessions from backend.

- [ ] **Step 3: Implement new chat**

Create session and navigate to `/agent/{sessionId}`.

- [ ] **Step 4: Render desktop layout**

Sidebar plus main pane.

- [ ] **Step 5: Render mobile layout**

Collapsible session list.

### Task 6: Build message list and composer

**Files:**

- Create: `agent-web/components/agent/message-list.tsx`
- Create: `agent-web/components/agent/message-item.tsx`
- Create: `agent-web/components/agent/composer.tsx`
- Create: `agent-web/components/agent/empty-state.tsx`
- Create: `agent-web/lib/hooks/use-agent-chat.ts`

- [ ] **Step 1: Load messages**

Fetch messages for selected session.

- [ ] **Step 2: Render messages**

Support user and assistant messages.

- [ ] **Step 3: Build composer**

Textarea, send button, cancel button.

- [ ] **Step 4: Send message**

Optimistically append user message, call backend, store runId.

- [ ] **Step 5: Validate input**

Reject empty and overlong messages.

### Task 7: Build run events and tool cards

**Files:**

- Create: `agent-web/components/agent/run-event-list.tsx`
- Create: `agent-web/components/agent/run-status.tsx`
- Create: `agent-web/components/agent/tool-call-card.tsx`
- Create: `agent-web/lib/hooks/use-run-events.ts`

- [ ] **Step 1: Poll run events**

Poll `GET /api/v1/agent/runs/{runId}/events`.

- [ ] **Step 2: Build assistant draft**

Concatenate `message.delta`.

- [ ] **Step 3: Show run status**

Map events to readable status rows.

- [ ] **Step 4: Show tool card**

Render selected/running/finished states.

- [ ] **Step 5: Stop polling**

Stop on terminal events.

### Task 8: Add cancel run behavior

**Files:**

- Modify: `agent-web/components/agent/composer.tsx`
- Modify: `agent-web/lib/hooks/use-agent-chat.ts`
- Modify: `agent-web/lib/api/agent.ts`

- [ ] **Step 1: Add cancel button during run**

Show cancel/stop icon when active run exists.

- [ ] **Step 2: Call cancel API**

Call `POST /api/v1/agent/runs/{runId}/cancel`.

- [ ] **Step 3: Update UI**

Show cancelled state and stop polling.

### Task 9: Add Docker Compose support

**Files:**

- Modify: `deploy/docker-compose.yml`
- Create: `agent-web/.env.example`

- [ ] **Step 1: Add env example**

Use Section 7.

- [ ] **Step 2: Add compose service**

Use Section 21.

- [ ] **Step 3: Start service**

```powershell
docker compose -f deploy/docker-compose.yml up agent-web
```

Expected:

```text
agent-web starts on http://localhost:5175
```

### Task 10: Final verification

- [ ] **Step 1: Typecheck**

```powershell
cd agent-web
npm run typecheck
```

- [ ] **Step 2: Build**

```powershell
cd agent-web
npm run build
```

- [ ] **Step 3: Start dev server**

```powershell
cd agent-web
npm run dev
```

- [ ] **Step 4: Browser desktop check**

Open:

```text
http://localhost:5175
```

Verify desktop layout.

- [ ] **Step 5: Browser mobile check**

Verify mobile layout at 390x844.

- [ ] **Step 6: Integration smoke**

With backend available:

```text
login
create session
send message
see run events
see assistant answer
refresh and recover messages
```

---

## 24. Acceptance Criteria

Phase 1.3 is complete when:

- `agent-web/` exists and runs on port 5175.
- Login page works with Spring Boot user login.
- AuthGuard protects Agent pages.
- Users can create and switch sessions.
- Users can send messages.
- UI shows optimistic user messages.
- UI tracks active run events.
- UI renders assistant draft from `message.delta`.
- UI renders tool selected/running/finished states.
- UI handles run failure.
- UI can cancel active run.
- Refreshing the page reloads persisted session messages.
- Desktop and mobile layouts are usable and non-overlapping.
- TypeScript typecheck passes.
- Production build succeeds.
- Frontend never calls FastAPI Agent Service directly.

---

## 25. Handoff To Next Phase

After this document is implemented, the next development document should be:

```text
Phase 1.4：Agent 工具注册与自动调用开发文档
```

That document should specialize:

- tool descriptor quality.
- tool schema exposure.
- natural language to tool arguments.
- tool execution bridge.
- tool result rendering.
- confirmation rules for sensitive tools.
