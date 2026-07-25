param(
    [ValidateSet("Validate", "CreatePr")]
    [string]$Mode = "Validate",
    [string]$BaseBranch = "dev",
    [switch]$RefreshRemote
)

$ErrorActionPreference = "Stop"
$Root = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
$ApprovedRepository = "crpi-e8y8tegbhx1vbpxm.cn-guangzhou.personal.cr.aliyuncs.com/aitools_wl/banana"

function Invoke-CheckedCommand {
    param(
        [Parameter(Mandatory = $true)][string]$Name,
        [Parameter(Mandatory = $true)][scriptblock]$Command
    )
    Write-Host "[RUN] $Name"
    & $Command
    if ($LASTEXITCODE -ne 0) {
        throw "$Name failed with exit code $LASTEXITCODE"
    }
    Write-Host "[OK]  $Name"
}

function Assert-CommandExists {
    param([Parameter(Mandatory = $true)][string]$Name)
    if (-not (Get-Command $Name -ErrorAction SilentlyContinue)) {
        throw "Required command is missing: $Name"
    }
}

Set-Location -LiteralPath $Root
foreach ($Command in @("git", "python", "node", "npm", "mvn", "docker")) {
    Assert-CommandExists $Command
}

$Branch = (& git branch --show-current).Trim()
if ($LASTEXITCODE -ne 0 -or -not $Branch) {
    throw "Could not resolve the current Git branch."
}
if ($Branch -eq $BaseBranch -or -not $Branch.StartsWith("codex/")) {
    throw "PPT releases must be prepared from a codex/* branch, not '$Branch'."
}

if ($RefreshRemote -or $Mode -eq "CreatePr") {
    Invoke-CheckedCommand "Refresh origin/$BaseBranch" { git fetch origin $BaseBranch }
}

$AheadBehind = (& git rev-list --left-right --count "origin/$BaseBranch...HEAD") -split "\s+"
if ($LASTEXITCODE -ne 0 -or $AheadBehind.Count -lt 2) {
    throw "Could not compare HEAD with origin/$BaseBranch."
}
if ([int]$AheadBehind[0] -gt 0) {
    throw "Current branch is behind origin/$BaseBranch by $($AheadBehind[0]) commit(s). Rebase before release."
}

$LockPath = Join-Path $Root "engines\versions.lock.json"
$Lock = Get-Content -Raw -Encoding UTF8 $LockPath | ConvertFrom-Json
$Engine = $Lock.engines.'banana-slides'
if ($Engine.releaseStatus -ne "READY") {
    throw "Banana releaseStatus must be READY."
}
if ([string]::IsNullOrWhiteSpace($Engine.productForkCommit) -or $Engine.productForkCommit -notmatch '^[0-9a-f]{40}$') {
    throw "Banana productForkCommit must be a full Git SHA."
}
$ExpectedImagePattern = '^' + [regex]::Escape($ApprovedRepository) + '@sha256:[0-9a-f]{64}$'
if ($Engine.image.reference -notmatch $ExpectedImagePattern) {
    throw "Banana image must use the approved ACR repository and an immutable digest."
}
$LocalEnvPath = Join-Path $Root ".env"
if (Test-Path -LiteralPath $LocalEnvPath) {
    $LocalImageLine = Get-Content -Encoding UTF8 $LocalEnvPath |
        Where-Object { $_ -match '^BANANA_SLIDES_IMAGE=' } |
        Select-Object -Last 1
    if ($LocalImageLine) {
        $LocalImage = $LocalImageLine.Substring("BANANA_SLIDES_IMAGE=".Length).Trim()
        if ($LocalImage -ne $Engine.image.reference) {
            throw "Local BANANA_SLIDES_IMAGE does not match engines/versions.lock.json."
        }
    }
}

$ForkPath = (Resolve-Path (Join-Path $Root "..\banana-slides-product") -ErrorAction SilentlyContinue)
if ($ForkPath) {
    $ForkStatus = @(& git -C $ForkPath.Path status --porcelain)
    if ($LASTEXITCODE -ne 0) {
        throw "Could not inspect the Banana product fork."
    }
    if ($ForkStatus.Count -gt 0) {
        throw "Banana product fork has uncommitted changes. Commit, test, publish, and update versions.lock.json first."
    }
    $ForkCommit = (& git -C $ForkPath.Path rev-parse HEAD).Trim()
    if ($ForkCommit -ne $Engine.productForkCommit) {
        throw "Banana fork HEAD ($ForkCommit) does not match versions.lock.json ($($Engine.productForkCommit))."
    }
} else {
    Write-Host "[INFO] Banana sibling checkout not found; immutable lock validation only."
}

Invoke-CheckedCommand "Git whitespace check" { git diff --check }
Invoke-CheckedCommand "Worker PPT transport tests" {
    python -m pytest worker/tests/test_model_client_failover.py worker/tests/test_outbound_http.py -q
}
Invoke-CheckedCommand "PPT frontend tests" {
    npm --prefix user-web test
}
Invoke-CheckedCommand "PPT frontend production build" {
    npm --prefix user-web run build
}
Invoke-CheckedCommand "PPT backend tests" {
    mvn -f backend/pom.xml -Dtest="Ppt*Test" test
}
Invoke-CheckedCommand "Deployment contract tests" {
    python -m unittest deploy/scripts/test_deploy_contracts.py
}
Invoke-CheckedCommand "PPT Compose configuration" {
    docker compose --env-file .env -f deploy/docker-compose.yml -f deploy/docker-compose.ppt.yml config --quiet
}

if ($Mode -eq "Validate") {
    Write-Host "[READY] Validation passed. Commit the reviewed changes, then run:"
    Write-Host "  powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\ppt-release.ps1 -Mode CreatePr -RefreshRemote"
    exit 0
}

$MainStatus = @(& git status --porcelain)
if ($MainStatus.Count -gt 0) {
    throw "Main repository has uncommitted changes. CreatePr requires a clean worktree."
}
Assert-CommandExists "gh"
Invoke-CheckedCommand "GitHub authentication" { gh auth status }
Invoke-CheckedCommand "Push $Branch" { git push --set-upstream origin $Branch }

$ExistingUrl = (& gh pr list --head $Branch --base $BaseBranch --state open --json url --jq '.[0].url').Trim()
if ($LASTEXITCODE -ne 0) {
    throw "Could not query existing pull requests."
}
if ($ExistingUrl) {
    Write-Host "[READY] Existing PR: $ExistingUrl"
    exit 0
}

$Body = @"
## Summary

- complete the platform-routed PPT generation workbench
- fix UTF-8 SSE decoding for OpenAI-compatible text models
- add production gates, authenticated PPT smoke, and rollback coverage

## Release behavior

This PR runs CI only. Merging it into `$BaseBranch` triggers the existing production delivery workflow.
"@
$PrUrl = (& gh pr create --base $BaseBranch --head $Branch --title "feat(ppt): complete workbench production delivery" --body $Body).Trim()
if ($LASTEXITCODE -ne 0 -or -not $PrUrl) {
    throw "Failed to create the pull request."
}
Write-Host "[READY] Pull request created: $PrUrl"
Write-Host "[INFO] Merge only after all required checks pass. The merge push to $BaseBranch performs production deployment."
