param(
    [string]$BaseUrl = "http://127.0.0.1:8080",
    [string]$Username = "ppt_smoke_local",
    [string]$Password = "123456",
    [string]$Topic = "Three practical ways small teams can use AI safely",
    [string]$OutputDirectory = "",
    [long]$TextModelConfigId = 0,
    [long]$ImageModelConfigId = 0,
    [ValidateRange(1, 30)]
    [int]$PollIntervalSeconds = 2,
    [ValidateRange(1, 120)]
    [int]$StageTimeoutMinutes = 70,
    [switch]$SkipDatabaseAssertions
)

$ErrorActionPreference = "Stop"
$Root = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path

function Import-DotEnv([string]$Path) {
    if (-not (Test-Path -LiteralPath $Path)) { return }
    Get-Content -LiteralPath $Path | ForEach-Object {
        $line = $_.Trim()
        if (-not $line -or $line.StartsWith("#")) { return }
        $index = $line.IndexOf("=")
        if ($index -le 0) { return }
        $name = $line.Substring(0, $index).Trim()
        $value = $line.Substring($index + 1).Trim().Trim('"').Trim("'")
        if ($name -and -not [Environment]::GetEnvironmentVariable($name, "Process")) {
            [Environment]::SetEnvironmentVariable($name, $value, "Process")
        }
    }
}

function ApiUrl([string]$Path) {
    return $BaseUrl.TrimEnd("/") + $Path
}

function HttpErrorDetail([System.Management.Automation.ErrorRecord]$Record) {
    $status = $null
    if ($Record.Exception.Response) {
        try { $status = [int]$Record.Exception.Response.StatusCode } catch { $status = $Record.Exception.Response.StatusCode }
    }
    $body = $Record.ErrorDetails.Message
    return "status=$status message=$($Record.Exception.Message) body=$body"
}

function Invoke-PptApi {
    param(
        [ValidateSet("GET", "POST", "PUT")]
        [string]$Method,
        [string]$Path,
        [object]$Body = $null,
        [string]$Token = ""
    )
    $params = @{
        Method = $Method
        Uri = ApiUrl $Path
        ContentType = "application/json"
        TimeoutSec = 30
        Headers = @{}
    }
    if ($Token) { $params.Headers.Authorization = "Bearer $Token" }
    if ($null -ne $Body) { $params.Body = $Body | ConvertTo-Json -Depth 12 }
    try {
        $response = Invoke-RestMethod @params
    } catch {
        throw "API $Method $Path failed: $(HttpErrorDetail $_)"
    }
    if ($response.PSObject.Properties.Name -contains "success" -and -not $response.success) {
        throw "API $Method $Path returned a business failure: $($response | ConvertTo-Json -Depth 8 -Compress)"
    }
    if ($response.PSObject.Properties.Name -contains "data") { return $response.data }
    return $response
}

function RequiredProperty([object]$Value, [string[]]$Names, [string]$Description) {
    foreach ($name in $Names) {
        if ($null -ne $Value -and $Value.PSObject.Properties.Name -contains $name) {
            $candidate = $Value.$name
            if ($null -ne $candidate -and -not [string]::IsNullOrWhiteSpace([string]$candidate)) { return $candidate }
        }
    }
    throw "Missing $Description. Tried: $($Names -join ', ')"
}

function Get-OrCreateToken {
    try {
        $login = Invoke-PptApi -Method POST -Path "/api/v1/auth/login" -Body @{ account = $Username; password = $Password }
        return RequiredProperty $login @("accessToken", "token") "login token"
    } catch {
        Write-Host "[INFO] Smoke user login failed; registering '$Username'."
        $register = Invoke-PptApi -Method POST -Path "/api/v1/auth/register" -Body @{
            username = $Username
            password = $Password
            nickname = "PPT Local Smoke"
        }
        return RequiredProperty $register @("accessToken", "token") "registration token"
    }
}

function Wait-PptJob([long]$JobId, [string]$Token, [string]$Stage) {
    $deadline = (Get-Date).AddMinutes($StageTimeoutMinutes)
    $lastSummary = ""
    do {
        $job = Invoke-PptApi -Method GET -Path "/api/v2/ppt/jobs/$JobId" -Token $Token
        $summary = "$($job.status) $($job.progress)% $($job.progressMessage)"
        if ($summary -ne $lastSummary) {
            Write-Host "[$Stage] $summary"
            $lastSummary = $summary
        }
        if ($job.status -eq "SUCCEEDED") {
            if ([int]$job.reservedCredits -gt 0 -and $job.creditState -ne "SETTLED") {
                throw "$Stage succeeded but credit state is '$($job.creditState)'"
            }
            return $job
        }
        if ($job.status -in @("FAILED", "CANCELLED")) {
            throw "$Stage ended as $($job.status): code=$($job.error.code) message=$($job.error.message)"
        }
        Start-Sleep -Seconds $PollIntervalSeconds
    } while ((Get-Date) -lt $deadline)
    throw "$Stage job $JobId did not finish within $StageTimeoutMinutes minutes"
}

function Assert-Deck([object]$Project, [string]$Stage) {
    $slides = @($Project.latestDeck.slides)
    if ($slides.Count -ne 3) { throw "$Stage expected exactly 3 slides, got $($slides.Count)" }
    if ($Stage -eq "GENERATE_OUTLINE" -and @($slides | Where-Object { [string]::IsNullOrWhiteSpace($_.title) }).Count -gt 0) {
        throw "Outline contains a slide without a title"
    }
    if ($Stage -eq "GENERATE_DESCRIPTIONS" -and @($slides | Where-Object { [string]::IsNullOrWhiteSpace($_.description) }).Count -gt 0) {
        throw "Descriptions contain an empty slide description"
    }
    if ($Stage -eq "GENERATE_IMAGES" -and @($slides | Where-Object { [string]::IsNullOrWhiteSpace($_.previewUrl) }).Count -gt 0) {
        throw "Images contain a slide without a platform preview URL"
    }
}

function Invoke-MySql([string]$Database, [string]$Sql) {
    $container = if ($env:MYSQL_CONTAINER_NAME) { $env:MYSQL_CONTAINER_NAME } else { "ai-supermarket-mysql" }
    $username = if ($env:MYSQL_USERNAME) { $env:MYSQL_USERNAME } else { "root" }
    $password = if ($env:MYSQL_PASSWORD) { $env:MYSQL_PASSWORD } else { "root123456" }
    $output = & docker exec -e "MYSQL_PWD=$password" $container mysql -u$username -N -B $Database -e $Sql 2>&1
    if ($LASTEXITCODE -ne 0) { throw "MySQL assertion query failed for database '$Database': $output" }
    return @($output | Where-Object { $_ -and $_ -notmatch '^mysql: \[Warning\]' })
}

function Assert-DatabaseState([long]$ProjectId, [string]$ExternalProjectId) {
    $platformDb = if ($env:MYSQL_DATABASE) { $env:MYSQL_DATABASE } else { "ai_supermarket_v1" }
    $jobSummary = Invoke-MySql $platformDb "SELECT CONCAT(COUNT(*),'|',SUM(status='SUCCEEDED'),'|',SUM(credit_state NOT IN ('SETTLED','NOT_REQUIRED')),'|',COUNT(DISTINCT user_id)) FROM ppt_jobs WHERE project_id=$ProjectId;"
    $jobParts = ([string]$jobSummary[0]).Split('|')
    if ($jobParts[0] -ne '4' -or $jobParts[1] -ne '4' -or $jobParts[2] -ne '0' -or $jobParts[3] -ne '1') {
        throw "Unexpected PPT job database summary: $($jobSummary[0])"
    }
    $invocations = [int](Invoke-MySql $platformDb "SELECT COUNT(*) FROM ppt_model_invocations WHERE project_id=$ProjectId;")[0]
    if ($invocations -lt 3) { throw "Expected platform model invocations for project $ProjectId, got $invocations" }
    $readyExports = [int](Invoke-MySql $platformDb "SELECT COUNT(*) FROM ppt_exports WHERE project_id=$ProjectId AND status='READY';")[0]
    if ($readyExports -ne 1) { throw "Expected one READY export, got $readyExports" }
    $receipts = [int](Invoke-MySql "banana_slides" "SELECT COUNT(*) FROM platform_submission_receipts WHERE platform_project_id=$ProjectId AND external_project_id='$ExternalProjectId';")[0]
    if ($receipts -lt 4) { throw "Expected at least four Banana stage receipts, got $receipts" }
}

function Assert-Ooxml([string]$Path) {
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $archive = [System.IO.Compression.ZipFile]::OpenRead($Path)
    try {
        $names = @($archive.Entries | ForEach-Object { $_.FullName })
        foreach ($required in @("[Content_Types].xml", "ppt/presentation.xml")) {
            if ($required -notin $names) { throw "Downloaded file is missing OOXML part '$required'" }
        }
        $slides = @($names | Where-Object { $_ -match '^ppt/slides/slide[0-9]+\.xml$' })
        if ($slides.Count -ne 3) { throw "Downloaded PPTX expected 3 slide XML parts, got $($slides.Count)" }
    } finally {
        $archive.Dispose()
    }
}

Import-DotEnv (Join-Path $Root ".env")
if (-not $OutputDirectory) { $OutputDirectory = Join-Path $Root ".local\ppt-smoke" }
New-Item -ItemType Directory -Path $OutputDirectory -Force | Out-Null

try {
    Invoke-RestMethod -Uri (ApiUrl "/api/health") -TimeoutSec 10 | Out-Null
} catch {
    throw "Backend is not ready at $BaseUrl. Start local services before running the PPT smoke test."
}

$token = Get-OrCreateToken
$status = Invoke-PptApi -Method GET -Path "/api/v2/ppt/status" -Token $token
if (-not $status.enabled) { throw "PPT workbench is disabled: $($status.message)" }
$capabilities = @(Invoke-PptApi -Method GET -Path "/api/v2/ppt/capabilities" -Token $token)
if (@($capabilities | Where-Object { $_.available }).Count -eq 0) { throw "No PPT engine is available" }

$options = Invoke-PptApi -Method GET -Path "/api/v2/ppt/model-options" -Token $token
$textModels = @($options.textModels)
$imageModels = @($options.imageModels)
if ($textModels.Count -eq 0 -or $imageModels.Count -eq 0) { throw "PPT requires at least one executable text model and image model" }
$textModel = if ($TextModelConfigId -gt 0) {
    $textModels | Where-Object { [long]$_.modelConfigId -eq $TextModelConfigId } | Select-Object -First 1
} else {
    $textModels | Where-Object { $_.recommended } | Select-Object -First 1
}
if (-not $textModel -and $TextModelConfigId -le 0) { $textModel = $textModels[0] }
if (-not $textModel) { throw "Requested text model $TextModelConfigId is not currently selectable" }

$imageModel = if ($ImageModelConfigId -gt 0) {
    $imageModels | Where-Object { [long]$_.modelConfigId -eq $ImageModelConfigId } | Select-Object -First 1
} else {
    $imageModels | Where-Object { $_.recommended } | Select-Object -First 1
}
if (-not $imageModel -and $ImageModelConfigId -le 0) { $imageModel = $imageModels[0] }
if (-not $imageModel) { throw "Requested image model $ImageModelConfigId is not currently selectable" }
Write-Host "[MODEL] text=$($textModel.displayName) image=$($imageModel.displayName)"

$runId = [guid]::NewGuid().ToString("N").Substring(0, 10)
$project = Invoke-PptApi -Method POST -Path "/api/v2/ppt/projects" -Token $token -Body @{
    title = "PPT smoke $runId"
    topic = $Topic
    creationType = "AI_GENERATED"
    language = "zh-CN"
    aspectRatio = "16:9"
    pageCount = 3
    textModelConfigId = [long]$textModel.modelConfigId
    imageModelConfigId = [long]$imageModel.modelConfigId
}
$projectId = [long](RequiredProperty $project @("projectId") "project id")
$binding = Invoke-PptApi -Method GET -Path "/api/v2/ppt/projects/$projectId/model-binding" -Token $token
if ([long]$binding.textModel.modelConfigId -ne [long]$textModel.modelConfigId -or [long]$binding.imageModel.modelConfigId -ne [long]$imageModel.modelConfigId) {
    throw "Project model binding does not match the selected platform models"
}
Write-Host "[PROJECT] id=$projectId title=$($project.title)"

$completedJobs = @()
foreach ($stage in @("GENERATE_OUTLINE", "GENERATE_DESCRIPTIONS", "GENERATE_IMAGES", "EXPORT_PPTX")) {
    $submitted = Invoke-PptApi -Method POST -Path "/api/v2/ppt/projects/$projectId/jobs" -Token $token -Body @{
        jobType = $stage
        clientRequestId = "ppt-smoke-$runId-$($stage.ToLowerInvariant())"
        payload = @{}
    }
    $job = Wait-PptJob -JobId ([long]$submitted.jobId) -Token $token -Stage $stage
    $completedJobs += $job
    if ($stage -ne "EXPORT_PPTX") {
        $project = Invoke-PptApi -Method GET -Path "/api/v2/ppt/projects/$projectId" -Token $token
        Assert-Deck $project $stage
    }
}

$exports = @(Invoke-PptApi -Method GET -Path "/api/v2/ppt/projects/$projectId/exports" -Token $token)
$readyExport = $exports | Where-Object { $_.status -eq "READY" -and $_.exportType -eq "PPTX" } | Select-Object -First 1
if (-not $readyExport) { throw "No READY PPTX export was registered" }
$pptxPath = Join-Path $OutputDirectory "ppt-smoke-$projectId.pptx"
$headers = @{ Authorization = "Bearer $token" }
Invoke-WebRequest -Uri (ApiUrl $readyExport.downloadUrl) -Headers $headers -OutFile $pptxPath -TimeoutSec 120
Assert-Ooxml $pptxPath

$platformDb = if ($env:MYSQL_DATABASE) { $env:MYSQL_DATABASE } else { "ai_supermarket_v1" }
$externalProjectId = [string](Invoke-MySql $platformDb "SELECT external_project_id FROM ppt_engine_bindings WHERE project_id=$projectId AND engine_code='banana-slides';")[0]
if (-not $externalProjectId) { throw "Missing Banana engine binding for project $projectId" }
if (-not $SkipDatabaseAssertions) { Assert-DatabaseState $projectId $externalProjectId }

$bananaDataDir = $env:BANANA_SLIDES_LOCAL_DATA_DIR
if ($bananaDataDir) {
    $hostExports = Join-Path $bananaDataDir "uploads\$externalProjectId\exports"
    if (-not (Get-ChildItem -LiteralPath $hostExports -Filter *.pptx -File -ErrorAction SilentlyContinue)) {
        throw "No PPTX was persisted in Banana host exports directory: $hostExports"
    }
}

Write-Host "PASS: PPT local closure completed"
Write-Host "  projectId: $projectId"
Write-Host "  externalProjectId: $externalProjectId"
Write-Host "  pptx: $pptxPath"
