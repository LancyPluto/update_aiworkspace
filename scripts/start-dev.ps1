param(
    [ValidateSet("auto", "0", "1")]
    [string]$StartInfra = $(if ($env:START_INFRA) { $env:START_INFRA } else { "auto" }),
    [switch]$InstallDeps
)

$ErrorActionPreference = "Stop"
$Root = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path

function Write-Section($Text) {
    Write-Host ""
    Write-Host "============================================"
    Write-Host "  $Text"
    Write-Host "============================================"
}

function Set-DefaultEnv($Name, $Value) {
    if (-not [Environment]::GetEnvironmentVariable($Name, "Process")) {
        [Environment]::SetEnvironmentVariable($Name, $Value, "Process")
    }
}

function Import-DotEnv($Path) {
    if (-not (Test-Path -LiteralPath $Path)) {
        return
    }
    Get-Content -LiteralPath $Path | ForEach-Object {
        $Line = $_.Trim()
        if (-not $Line -or $Line.StartsWith("#")) {
            return
        }
        $Index = $Line.IndexOf("=")
        if ($Index -le 0) {
            return
        }
        $Name = $Line.Substring(0, $Index).Trim()
        $Value = $Line.Substring($Index + 1).Trim().Trim('"').Trim("'")
        if ($Name) {
            [Environment]::SetEnvironmentVariable($Name, $Value, "Process")
        }
    }
}

function Require-Command($Name, $Hint) {
    if (-not (Get-Command $Name -ErrorAction SilentlyContinue)) {
        throw "Missing $Hint ($Name)."
    }
    Write-Host "[OK] Found $Hint"
}

function Run-Command($WorkingDirectory, $FilePath, [string[]]$Arguments) {
    Push-Location $WorkingDirectory
    try {
        & $FilePath @Arguments
        if ($LASTEXITCODE -ne 0) {
            throw "$FilePath $($Arguments -join ' ') failed with exit code $LASTEXITCODE"
        }
    } finally {
        Pop-Location
    }
}

function Install-NodeDeps($RelativePath) {
    $Path = Join-Path $Root $RelativePath
    Write-Section "Checking npm deps: $RelativePath"
    if (Test-Path -LiteralPath (Join-Path $Path "node_modules")) {
        Write-Host "[OK] node_modules exists"
        return
    }
    if (-not $InstallDeps) {
        Write-Host "[SKIP] node_modules missing. Run with -InstallDeps to install automatically."
        return
    }
    Run-Command $Path "npm" @("install")
}

function Install-PythonDeps($RelativePath) {
    $Path = Join-Path $Root $RelativePath
    $Requirements = Join-Path $Path "requirements.txt"
    Write-Section "Checking Python deps: $RelativePath"
    if (-not (Test-Path -LiteralPath $Requirements)) {
        Write-Host "[OK] No requirements.txt"
        return
    }
    if (-not $InstallDeps) {
        Write-Host "[SKIP] requirements.txt found. Run with -InstallDeps to install automatically."
        return
    }
    Run-Command $Path "python" @("-m", "pip", "install", "-r", "requirements.txt")
}

function Start-DevWindow($Title, $RelativePath, $Command) {
    $Path = Join-Path $Root $RelativePath
    $EscapedPath = $Path.Replace("'", "''")
    $EscapedCommand = $Command.Replace("'", "''")
    $Script = "Set-Location -LiteralPath '$EscapedPath'; Write-Host '[$Title] $Command'; $EscapedCommand"
    Start-Process powershell -ArgumentList @("-NoExit", "-NoProfile", "-ExecutionPolicy", "Bypass", "-Command", $Script) -WindowStyle Normal
    Write-Host "[OK] Started $Title"
}

function Test-HttpReady($Url, $Seconds) {
    $Deadline = (Get-Date).AddSeconds($Seconds)
    while ((Get-Date) -lt $Deadline) {
        try {
            Invoke-WebRequest -Uri $Url -UseBasicParsing -TimeoutSec 2 | Out-Null
            return $true
        } catch {
            Start-Sleep -Seconds 2
        }
    }
    return $false
}

function Start-InfraIfNeeded {
    if ($StartInfra -eq "0") {
        Write-Host "[SKIP] Infra startup disabled by START_INFRA=0"
        return
    }

    $Docker = Get-Command docker -ErrorAction SilentlyContinue
    if (-not $Docker) {
        if ($StartInfra -eq "1") {
            throw "START_INFRA=1 but Docker was not found."
        }
        Write-Host "[SKIP] Docker not found. Assuming local MySQL/Redis are already running."
        return
    }

    Write-Section "Starting infra: MySQL + Redis"
    $DeployPath = Join-Path $Root "deploy"
    Push-Location $DeployPath
    try {
        & docker compose version *> $null
        if ($LASTEXITCODE -eq 0) {
            & docker compose up -d mysql redis
        } else {
            & docker-compose up -d mysql redis
        }
        if ($LASTEXITCODE -ne 0) {
            throw "Failed to start MySQL/Redis."
        }
    } finally {
        Pop-Location
    }

    Write-Host "[..] Waiting for MySQL container..."
    $Deadline = (Get-Date).AddSeconds(90)
    while ((Get-Date) -lt $Deadline) {
        cmd /c "docker exec ai-supermarket-mysql mysqladmin ping -uroot -proot123456 --silent >nul 2>nul"
        if ($LASTEXITCODE -eq 0) {
            Write-Host "[OK] MySQL + Redis are ready"
            return
        }
        Start-Sleep -Seconds 2
    }
    throw "Timed out waiting for MySQL container."
}

Import-DotEnv (Join-Path $Root ".env")

Set-DefaultEnv "INTERNAL_API_TOKEN" "local-internal-token"
Set-DefaultEnv "BACKEND_INTERNAL_BASE_URL" "http://127.0.0.1:8080"
Set-DefaultEnv "AGENT_SERVICE_BASE_URL" "http://127.0.0.1:8090"
Set-DefaultEnv "AGENT_ENABLED" "true"
Set-DefaultEnv "SERVER_PORT" "8080"
Set-DefaultEnv "REDIS_HOST" "127.0.0.1"
Set-DefaultEnv "REDIS_PORT" "6379"
Set-DefaultEnv "AI_TASK_QUEUE" "ai:task:queue"

Write-Section "AI Tool Market - Local Dev Launcher"
Write-Host "Root:          $Root"
Write-Host "Infra mode:    $StartInfra"
Write-Host "Backend:       http://localhost:8080"
Write-Host "Agent Service: http://localhost:8090"
Write-Host "User Web:      http://localhost:5173"
Write-Host "Admin Web:     http://localhost:5174"
Write-Host "Worker:        Redis queue consumer"

Require-Command "java" "JDK 17+"
Require-Command "mvn" "Maven 3.8+"
Require-Command "npm" "Node.js/npm"
Require-Command "python" "Python 3.11+"

Start-InfraIfNeeded

Install-NodeDeps "user-web"
Install-NodeDeps "admin-frontend"
Install-PythonDeps "agent-service"
Install-PythonDeps "worker"

Write-Section "Starting app services on host"
Start-DevWindow "Backend :8080" "backend" "mvn spring-boot:run"

Write-Host "[..] Waiting for backend health..."
if (Test-HttpReady "http://localhost:8080/api/health" 80) {
    Write-Host "[OK] Backend is ready"
} else {
    Write-Host "[WARN] Backend did not answer health check yet. Check the Backend window."
}

Start-DevWindow "Agent Service :8090" "agent-service" "python -m uvicorn app.main:app --reload --port 8090"

Write-Host "[..] Waiting for agent-service health..."
if (Test-HttpReady "http://localhost:8090/health" 40) {
    Write-Host "[OK] Agent Service is ready"
} else {
    Write-Host "[WARN] Agent Service did not answer health check yet. Check the Agent Service window."
}

Start-DevWindow "Worker Queue" "worker" "python main.py"
Start-DevWindow "User Web :5173" "user-web" "npm run dev"
Start-DevWindow "Admin Web :5174" "admin-frontend" "npm run dev"

Write-Section "Local dev services launched"
Write-Host "Backend API:     http://localhost:8080"
Write-Host "Backend health:  http://localhost:8080/api/health"
Write-Host "Agent health:    http://localhost:8090/health"
Write-Host "User Web:        http://localhost:5173"
Write-Host "Admin Web:       http://localhost:5174"
Write-Host ""
Write-Host "Close each opened terminal window to stop that service."
