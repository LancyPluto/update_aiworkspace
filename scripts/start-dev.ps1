param(
    [ValidateSet("auto", "0", "1")]
    [string]$StartInfra = $(if ($env:START_INFRA) { $env:START_INFRA } else { "auto" }),
    [ValidateSet("check", "auto", "0", "1")]
    [string]$ApplySql = $(if ($env:APPLY_SQL) { $env:APPLY_SQL } else { "check" }),
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

$VenvDir = Join-Path $Root ".venv"
$VenvPython = Join-Path $VenvDir "Scripts\python.exe"
$RequirementsDev = Join-Path $Root "requirements-dev.txt"

function Ensure-PythonVenv {
    if (Test-Path -LiteralPath $VenvPython) {
        & $VenvPython --version *> $null
        if ($LASTEXITCODE -eq 0) {
            return
        }
        $ResolvedVenv = (Resolve-Path -LiteralPath $VenvDir -ErrorAction SilentlyContinue).Path
        if ($ResolvedVenv -and $ResolvedVenv.StartsWith($Root, [StringComparison]::OrdinalIgnoreCase)) {
            Write-Host "[WARN] Existing .venv is not executable; recreating it."
            Remove-Item -LiteralPath $ResolvedVenv -Recurse -Force
        } else {
            throw "Refusing to recreate venv outside repo root: $VenvDir"
        }
    }
    Write-Section "Creating project Python venv (.venv)"
    Run-Command $Root "python" @("-m", "venv", $VenvDir)
    if (-not (Test-Path -LiteralPath $VenvPython)) {
        throw "Failed to create venv at $VenvDir"
    }
    Write-Host "[OK] Created $VenvDir"
}

function Test-PythonVenvReady {
    if (-not (Test-Path -LiteralPath $VenvPython)) {
        return $false
    }
    & $VenvPython -c "import uvicorn, pika" 2>$null
    return $LASTEXITCODE -eq 0
}

function Sync-PythonVenvDeps {
    Write-Section "Checking Python venv (.venv)"
    Ensure-PythonVenv
    if (-not (Test-Path -LiteralPath $RequirementsDev)) {
        throw "Missing requirements-dev.txt at repo root."
    }
    if (-not $InstallDeps -and (Test-PythonVenvReady)) {
        Write-Host "[OK] Python venv deps ready"
        return
    }
    if ($InstallDeps) {
        Write-Host "[..] Reinstalling Python deps (-InstallDeps)"
    } else {
        Write-Host "[..] Installing Python deps (first run or incomplete venv)"
    }
    Run-Command $Root $VenvPython @("-m", "pip", "install", "-U", "pip", "wheel")
    Run-Command $Root $VenvPython @("-m", "pip", "install", "-r", $RequirementsDev)
    if (-not (Test-PythonVenvReady)) {
        throw "Python venv install finished but uvicorn/pika are still missing."
    }
    Write-Host "[OK] Python venv deps installed"
}

function Get-VenvPythonCommand {
    Ensure-PythonVenv
    return "'$($VenvPython.Replace("'", "''"))'"
}

function Install-NodeDeps($RelativePath) {
    $Path = Join-Path $Root $RelativePath
    Write-Section "Checking npm deps: $RelativePath"
    if ((Test-Path -LiteralPath (Join-Path $Path "node_modules")) -and -not $InstallDeps) {
        Write-Host "[OK] node_modules exists"
        return
    }
    if ($InstallDeps) {
        Write-Host "[..] npm install (-InstallDeps)"
    } else {
        Write-Host "[..] npm install (first run or missing node_modules)"
    }
    Run-Command $Path "npm" @("install")
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

function Get-SqlMigrationFiles {
    $SqlRoot = Join-Path $Root "sql"
    if (-not (Test-Path -LiteralPath $SqlRoot)) {
        return @()
    }

    $Files = @(Get-ChildItem -LiteralPath $SqlRoot -Filter "*.sql" -File -ErrorAction SilentlyContinue)
    $MigrationDir = Join-Path $SqlRoot "migrations"
    if (Test-Path -LiteralPath $MigrationDir) {
        $Files += Get-ChildItem -LiteralPath $MigrationDir -Filter "*.sql" -File -ErrorAction SilentlyContinue
    }

    return @($Files | Sort-Object FullName)
}

function Get-SqlMigrationName($File) {
    $SqlRoot = (Resolve-Path (Join-Path $Root "sql")).Path
    $FullName = (Resolve-Path $File.FullName).Path
    if ($FullName.StartsWith($SqlRoot, [StringComparison]::OrdinalIgnoreCase)) {
        $Relative = $FullName.Substring($SqlRoot.Length).TrimStart("\", "/")
        if ($Relative -notmatch "[\\/]") {
            return $File.Name
        }
        return $Relative.Replace("\", "/")
    }
    return $File.Name
}

function New-MysqlRunner {
    $MysqlDb = if ($env:MYSQL_DATABASE) { $env:MYSQL_DATABASE } else { "ai_supermarket_v1" }
    $MysqlUser = if ($env:MYSQL_USERNAME) { $env:MYSQL_USERNAME } else { "root" }
    $MysqlPass = if ($env:MYSQL_PASSWORD) { $env:MYSQL_PASSWORD } else { "root123456" }
    $MysqlHost = if ($env:MYSQL_HOST) { $env:MYSQL_HOST } else { "127.0.0.1" }
    $MysqlPort = if ($env:MYSQL_PORT) { $env:MYSQL_PORT } else { "3307" }
    $ContainerName = if ($env:MYSQL_CONTAINER) { $env:MYSQL_CONTAINER } else { "ai-supermarket-mysql" }

    $Docker = Get-Command docker -ErrorAction SilentlyContinue
    if ($Docker) {
        $Containers = @(& docker ps --format "{{.Names}}" 2>$null)
        if ($LASTEXITCODE -eq 0 -and ($Containers -contains $ContainerName)) {
            return @{
                Mode = "docker"
                Container = $ContainerName
                Database = $MysqlDb
                User = $MysqlUser
                Password = $MysqlPass
            }
        }
    }

    if (Get-Command mysql -ErrorAction SilentlyContinue) {
        return @{
            Mode = "host"
            Host = $MysqlHost
            Port = $MysqlPort
            Database = $MysqlDb
            User = $MysqlUser
            Password = $MysqlPass
        }
    }

    return $null
}

function Invoke-MysqlScalar($Runner, $Sql) {
    $PreviousErrorActionPreference = $ErrorActionPreference
    $ErrorActionPreference = "Continue"
    try {
        if ($Runner.Mode -eq "docker") {
            $Output = & docker exec -e "MYSQL_PWD=$($Runner.Password)" $Runner.Container mysql "-u$($Runner.User)" $Runner.Database -N -B -e $Sql 2>&1
        } else {
            $PreviousMysqlPwd = $env:MYSQL_PWD
            $env:MYSQL_PWD = $Runner.Password
            try {
                $Output = & mysql "-h$($Runner.Host)" "-P$($Runner.Port)" "-u$($Runner.User)" $Runner.Database -N -B -e $Sql 2>&1
            } finally {
                $env:MYSQL_PWD = $PreviousMysqlPwd
            }
        }
    } finally {
        $ErrorActionPreference = $PreviousErrorActionPreference
    }
    if ($LASTEXITCODE -ne 0) {
        throw "mysql query failed: $($Output -join "`n")"
    }
    $Rows = @($Output | Where-Object { $_ -notmatch "Using a password" })
    if ($Rows.Count -eq 0) {
        return ""
    }
    return ($Rows[-1].ToString()).Trim()
}

function Invoke-MysqlCommand($Runner, $Sql) {
    $PreviousErrorActionPreference = $ErrorActionPreference
    $ErrorActionPreference = "Continue"
    try {
        if ($Runner.Mode -eq "docker") {
            $Output = & docker exec -e "MYSQL_PWD=$($Runner.Password)" $Runner.Container mysql "-u$($Runner.User)" $Runner.Database -e $Sql 2>&1
        } else {
            $PreviousMysqlPwd = $env:MYSQL_PWD
            $env:MYSQL_PWD = $Runner.Password
            try {
                $Output = & mysql "-h$($Runner.Host)" "-P$($Runner.Port)" "-u$($Runner.User)" $Runner.Database -e $Sql 2>&1
            } finally {
                $env:MYSQL_PWD = $PreviousMysqlPwd
            }
        }
    } finally {
        $ErrorActionPreference = $PreviousErrorActionPreference
    }
    if ($LASTEXITCODE -ne 0) {
        throw "mysql command failed: $($Output -join "`n")"
    }
}

function Invoke-MysqlFile($Runner, $Path) {
    $SqlText = Get-Content -LiteralPath $Path -Raw
    $PreviousErrorActionPreference = $ErrorActionPreference
    $ErrorActionPreference = "Continue"
    try {
        if ($Runner.Mode -eq "docker") {
            $Output = $SqlText | & docker exec -i -e "MYSQL_PWD=$($Runner.Password)" $Runner.Container mysql "-u$($Runner.User)" $Runner.Database 2>&1
        } else {
            $PreviousMysqlPwd = $env:MYSQL_PWD
            $env:MYSQL_PWD = $Runner.Password
            try {
                $Output = $SqlText | & mysql "-h$($Runner.Host)" "-P$($Runner.Port)" "-u$($Runner.User)" $Runner.Database 2>&1
            } finally {
                $env:MYSQL_PWD = $PreviousMysqlPwd
            }
        }
    } finally {
        $ErrorActionPreference = $PreviousErrorActionPreference
    }
    return @{
        Code = $LASTEXITCODE
        Output = ($Output -join "`n")
    }
}

function Escape-SqlString($Value) {
    return $Value.Replace("\", "\\").Replace("'", "''")
}

function Apply-LocalSqlMigrations {
    if ($ApplySql -eq "0") {
        Write-Host "[SKIP] SQL migration check disabled by APPLY_SQL=0"
        return
    }

    Write-Section "Checking local SQL migrations"
    $Files = @(Get-SqlMigrationFiles)
    if ($Files.Count -eq 0) {
        Write-Host "[OK] No SQL files found"
        return
    }

    $Runner = New-MysqlRunner
    if (-not $Runner) {
        $Message = "No MySQL runner found. Start Docker MySQL or install mysql client."
        if ($ApplySql -eq "1") {
            throw $Message
        }
        Write-Host "[SKIP] $Message"
        return
    }

    Write-Host "[OK] MySQL runner: $($Runner.Mode)"
    Invoke-MysqlCommand $Runner @"
CREATE TABLE IF NOT EXISTS _sql_migration_log (
  name VARCHAR(255) NOT NULL PRIMARY KEY,
  applied_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
"@

    if ($ApplySql -eq "check" -or $ApplySql -eq "auto") {
        $Pending = @()
        foreach ($File in $Files) {
            $Name = Get-SqlMigrationName $File
            $EscapedName = Escape-SqlString $Name
            $Exists = Invoke-MysqlScalar $Runner "SELECT COUNT(*) FROM _sql_migration_log WHERE name='$EscapedName';"
            if ($Exists -ne "1") {
                $Pending += $Name
            }
        }

        if ($Pending.Count -eq 0) {
            Write-Host "[OK] No pending SQL files"
            return
        }

        Write-Host "[WARN] Pending SQL files: $($Pending.Count)"
        $Pending | Select-Object -First 12 | ForEach-Object { Write-Host "  $_" }
        if ($Pending.Count -gt 12) {
            Write-Host "  ... $($Pending.Count - 12) more"
        }
        Write-Host "[INFO] Startup will continue without applying SQL. To apply explicitly: `$env:APPLY_SQL='1'; .\start-dev.bat"
        return
    }

    $Applied = 0
    $Skipped = 0
    $Failed = 0
    foreach ($File in $Files) {
        $Name = Get-SqlMigrationName $File
        $EscapedName = Escape-SqlString $Name
        $Exists = Invoke-MysqlScalar $Runner "SELECT COUNT(*) FROM _sql_migration_log WHERE name='$EscapedName';"
        if ($Exists -eq "1") {
            $Skipped++
            continue
        }

        if ($Name -eq "001_init_v1.sql") {
            $HasUsers = Invoke-MysqlScalar $Runner "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name='users';"
            if ($HasUsers -eq "1") {
                Write-Host "  SKIP $Name (schema already initialized)"
                Invoke-MysqlCommand $Runner "INSERT IGNORE INTO _sql_migration_log (name) VALUES ('$EscapedName');"
                $Skipped++
                continue
            }
        }

        Write-Host "  RUN  $Name"
        $Result = Invoke-MysqlFile $Runner $File.FullName
        if ($Result.Code -eq 0) {
            Invoke-MysqlCommand $Runner "INSERT INTO _sql_migration_log (name) VALUES ('$EscapedName');"
            $Applied++
            continue
        }

        if ($Result.Output -match "Duplicate (column|key|entry)|already exists|1060|1061|1062") {
            Write-Host "    idempotent skip: $($Result.Output.Split("`n")[0])"
            Invoke-MysqlCommand $Runner "INSERT IGNORE INTO _sql_migration_log (name) VALUES ('$EscapedName');"
            $Applied++
            continue
        }

        Write-Host "    FAILED: $($Result.Output.Split("`n")[0])"
        $Failed++
    }

    Write-Host "SQL summary: applied=$Applied skipped=$Skipped failed=$Failed"
    if ($Failed -gt 0 -and $ApplySql -eq "1") {
        throw "One or more SQL migrations failed."
    }
    if ($Failed -gt 0) {
        Write-Host "[WARN] Some SQL migrations failed. Backend startup will continue; check the SQL output above."
    }
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

    $UseRabbitMq = $env:TASK_QUEUE_BACKEND -and $env:TASK_QUEUE_BACKEND.Trim().ToLowerInvariant() -eq "rabbitmq"
    $InfraServices = @("mysql", "redis")
    if ($UseRabbitMq) {
        $InfraServices += "rabbitmq"
    }

    Write-Section ("Starting infra: " + ($InfraServices -join " + "))
    $DeployPath = Join-Path $Root "deploy"
    Push-Location $DeployPath
    try {
        & docker compose version *> $null
        if ($LASTEXITCODE -eq 0) {
            $ComposeArgs = @("compose", "up", "-d") + $InfraServices
            & docker @ComposeArgs
        } else {
            $ComposeArgs = @("up", "-d") + $InfraServices
            & docker-compose @ComposeArgs
        }
        if ($LASTEXITCODE -ne 0) {
            throw "Failed to start infra services: $($InfraServices -join ', ')."
        }
    } finally {
        Pop-Location
    }

    Write-Host "[..] Waiting for containers: $($InfraServices -join ', ')..."
    $Deadline = (Get-Date).AddSeconds(90)
    while ((Get-Date) -lt $Deadline) {
        cmd /c "docker exec ai-supermarket-mysql mysqladmin ping -uroot -proot123456 --silent >nul 2>nul"
        $MysqlReady = $LASTEXITCODE -eq 0
        cmd /c "docker exec ai-supermarket-redis redis-cli ping >nul 2>nul"
        $RedisReady = $LASTEXITCODE -eq 0
        $RabbitReady = -not $UseRabbitMq
        if ($UseRabbitMq) {
            cmd /c "docker exec ai-supermarket-rabbitmq rabbitmq-diagnostics -q ping >nul 2>nul"
            $RabbitReady = $LASTEXITCODE -eq 0
        }
        if ($MysqlReady -and $RedisReady -and $RabbitReady) {
            Write-Host "[OK] Infra services are ready: $($InfraServices -join ', ')"
            return
        }
        Start-Sleep -Seconds 2
    }
    throw "Timed out waiting for infra services: $($InfraServices -join ', ')."
}

Import-DotEnv (Join-Path $Root ".env")

Set-DefaultEnv "INTERNAL_API_TOKEN" "local-internal-token"
Set-DefaultEnv "BACKEND_INTERNAL_BASE_URL" "http://127.0.0.1:8080"
Set-DefaultEnv "AGENT_SERVICE_BASE_URL" "http://127.0.0.1:8090"
Set-DefaultEnv "AGENT_ENABLED" "true"
Set-DefaultEnv "SERVER_PORT" "8080"
Set-DefaultEnv "MYSQL_HOST" "127.0.0.1"
Set-DefaultEnv "MYSQL_PORT" "3307"
Set-DefaultEnv "MYSQL_DATABASE" "ai_supermarket_v1"
Set-DefaultEnv "MYSQL_USERNAME" "root"
Set-DefaultEnv "MYSQL_PASSWORD" "root123456"
Set-DefaultEnv "REDIS_HOST" "127.0.0.1"
Set-DefaultEnv "REDIS_PORT" "6379"
Set-DefaultEnv "AI_TASK_QUEUE" "ai:task:queue"
Set-DefaultEnv "TASK_QUEUE_BACKEND" "rabbitmq"
Set-DefaultEnv "RABBITMQ_HOST" "127.0.0.1"
Set-DefaultEnv "RABBITMQ_PORT" "5672"
Set-DefaultEnv "RABBITMQ_USERNAME" "guest"
Set-DefaultEnv "RABBITMQ_PASSWORD" "guest"
Set-DefaultEnv "RABBITMQ_TASK_QUEUE" "ai.tool.normal"
Set-DefaultEnv "GENERATED_MEDIA_DIR" (Join-Path $Root "data\generated-media")
Set-DefaultEnv "GENERATED_MEDIA_PUBLIC_BASE_URL" "/generated"

Write-Section "AI Tool Market - Local Dev Launcher"
Write-Host "Root:          $Root"
Write-Host "Infra mode:    $StartInfra"
Write-Host "SQL check:     $ApplySql"
Write-Host "Backend:       http://localhost:8080"
Write-Host "Agent Service: http://localhost:8090"
Write-Host "User Web:      http://localhost:5173"
Write-Host "Admin Web:     http://localhost:5174"
Write-Host "Queue backend: $env:TASK_QUEUE_BACKEND"
Write-Host "Worker:        $env:TASK_QUEUE_BACKEND queue consumer"

Require-Command "java" "JDK 17+"
Require-Command "mvn" "Maven 3.8+"
Require-Command "npm" "Node.js/npm"
Require-Command "python" "Python 3.11+"

Start-InfraIfNeeded
Apply-LocalSqlMigrations

Install-NodeDeps "user-web"
Install-NodeDeps "admin-frontend"
Sync-PythonVenvDeps

$VenvPyCmd = Get-VenvPythonCommand

Write-Section "Starting app services on host"
Start-DevWindow "Backend :8080" "backend" "mvn spring-boot:run"

Write-Host "[..] Waiting for backend health..."
if (Test-HttpReady "http://localhost:8080/api/health" 80) {
    Write-Host "[OK] Backend is ready"
} else {
    Write-Host "[WARN] Backend did not answer health check yet. Check the Backend window."
}

Start-DevWindow "Agent Service :8090" "agent-service" "$VenvPyCmd -m uvicorn app.main:app --reload --port 8090"

Write-Host "[..] Waiting for agent-service health..."
if (Test-HttpReady "http://localhost:8090/health" 40) {
    Write-Host "[OK] Agent Service is ready"
} else {
    Write-Host "[WARN] Agent Service did not answer health check yet. Check the Agent Service window."
}

Start-DevWindow "Worker Queue" "worker" "$VenvPyCmd main.py"
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
