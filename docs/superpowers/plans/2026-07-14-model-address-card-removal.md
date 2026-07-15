# Model Address Card Removal Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Remove the obsolete account-address inheritance card and reset action from the admin model editor without changing persisted model data or backend behavior.

**Architecture:** Keep the existing account selector and account health messaging intact. Remove only the address-status JSX and its two private URL-comparison helpers, then protect the boundary with a source-contract test.

**Tech Stack:** Next.js 16, React 19, TypeScript, Node.js built-in test runner

---

### Task 1: Remove the obsolete address card

**Files:**
- Create: `admin-frontend/components/admin/model-address-card-removal.test.mjs`
- Modify: `admin-frontend/components/admin/unified-api-settings.tsx:520-526`
- Modify: `admin-frontend/components/admin/unified-api-settings.tsx:2187-2241`

- [ ] **Step 1: Write the failing source-contract test**

```javascript
import assert from "node:assert/strict"
import { readFile } from "node:fs/promises"
import test from "node:test"

const source = await readFile(new URL("./unified-api-settings.tsx", import.meta.url), "utf8")

test("model editor omits the obsolete account address inheritance card", () => {
  assert.doesNotMatch(source, /继承账号地址/)
  assert.doesNotMatch(source, /模型覆盖地址/)
  assert.doesNotMatch(source, /改回继承账号地址/)
  assert.doesNotMatch(source, /当前模型保存了独立 baseUrl/)
})

test("model editor removes helpers used only by the obsolete card", () => {
  assert.doesNotMatch(source, /function normalizeOptionalUrl/)
  assert.doesNotMatch(source, /function sameBaseUrl/)
})
```

- [ ] **Step 2: Run the test and verify it fails**

Run: `node --test components/admin/model-address-card-removal.test.mjs` from `admin-frontend`.

Expected: FAIL because `unified-api-settings.tsx` still contains the inheritance-card text and helpers.

- [ ] **Step 3: Remove the address card and dead helpers**

Delete `normalizeOptionalUrl` and `sameBaseUrl`. Replace the bound-account block with the existing account status and probe warning only:

```tsx
{modelForm.vendorAccountId ? (() => {
  const account = accountById.get(modelForm.vendorAccountId)
  return (
    <div className="space-y-2">
      <p className={`text-xs ${account && hasAccountCredential(account) ? "text-muted-foreground" : "text-amber-700"}`}>
        {account
          ? `${account.vendorLabel || account.vendorCode} · ${accountCredentialLabel(account)} · ${account.healthStatus || "UNKNOWN"}`
          : `账号 #${modelForm.vendorAccountId} 详情未加载，请重新选择一个可用账户`}
      </p>
      {!modelForm.id && account && !accountProbePassed(account) ? (
        <p className="rounded-md border border-amber-200 bg-amber-50 px-3 py-2 text-xs text-amber-800">
          该账户尚未探活通过，新模型将先以停用状态保存。请先点账户闪电测试，成功后再启用模型。
        </p>
      ) : null}
    </div>
  )
})() : (
  <p className="text-xs text-amber-700">必须绑定一个厂商账户；API Key/AK/SK 只在账户里维护。</p>
)}
```

- [ ] **Step 4: Run the focused tests**

Run: `node --test components/admin/model-address-card-removal.test.mjs components/admin/model-config-wording.test.mjs components/admin/vendor-balance-display.test.mjs` from `admin-frontend`.

Expected: all tests PASS.

- [ ] **Step 5: Commit the focused change**

```bash
git add admin-frontend/components/admin/model-address-card-removal.test.mjs admin-frontend/components/admin/unified-api-settings.tsx
git commit --only -m "fix: remove model address inheritance card" -- admin-frontend/components/admin/model-address-card-removal.test.mjs admin-frontend/components/admin/unified-api-settings.tsx
```

### Task 2: Verify the admin application

**Files:**
- Verify: `admin-frontend/components/admin/unified-api-settings.tsx`

- [ ] **Step 1: Run all admin tests**

Run: `npm test` from `admin-frontend`.

Expected: all Node test suites PASS.

- [ ] **Step 2: Run TypeScript validation**

Run: `npm run typecheck` from `admin-frontend`.

Expected: exit code 0 with no TypeScript errors.

- [ ] **Step 3: Run the production build**

Run: `npm run build` from `admin-frontend`.

Expected: Next.js production build completes successfully.

- [ ] **Step 4: Confirm scope**

Run: `git diff HEAD^ -- admin-frontend/components/admin/model-address-card-removal.test.mjs admin-frontend/components/admin/unified-api-settings.tsx`.

Expected: the diff contains only the contract test, address-card JSX removal, and deletion of the two dead helpers.
