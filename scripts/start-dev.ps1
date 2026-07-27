param(
    [ValidateSet("auto", "0", "1")]
    [string]$StartInfra = $(if ($env:START_INFRA) { $env:START_INFRA } else { "auto" }),
    [ValidateSet("auto", "0", "1")]
    [string]$StartPpt = $(if ($env:START_PPT) { $env:START_PPT } else { "auto" }),
    [ValidateSet("check", "auto", "0", "1")]
    [string]$ApplySql = $(if ($env:APPLY_SQL) { $env:APPLY_SQL } else { "check" }),
    [switch]$InstallDeps,
    [switch]$PreflightOnly,
    [ValidateRange(1, 16)]
    [int]$WorkerCount = $(if ($env:WORKER_PROCESS_COUNT) { [int]$env:WORKER_PROCESS_COUNT } else { 2 })
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
    & $VenvPython -c "import uvicorn, pika, prometheus_client" 2>$null
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
        throw "Python venv install finished but uvicorn/pika/prometheus_client are still missing."
    }
    Write-Host "[OK] Python venv deps installed"
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
    $EscapedTitle = $Title.Replace("'", "''")
    # Do not escape $Command — doubling quotes breaks paths like 'D:\...\python.exe'.
    $Script = "Set-Location -LiteralPath '$EscapedPath'; Write-Host '[$EscapedTitle]'; $Command"
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

function Set-LocalContainerProxyFromWindows {
    if ([Environment]::GetEnvironmentVariable("LOCAL_CONTAINER_PROXY_URL", "Process")) {
        Write-Host "[PROXY] Local container proxy: configured explicitly"
        return
    }
    if (-not $IsWindows -and $env:OS -ne "Windows_NT") {
        return
    }
    try {
        $settings = Get-ItemProperty "HKCU:\Software\Microsoft\Windows\CurrentVersion\Internet Settings"
        if ([int]$settings.ProxyEnable -ne 1) {
            return
        }
        $match = [regex]::Match([string]$settings.ProxyServer, '(?:(?:https?|socks)=)?(?:127\.0\.0\.1|localhost):(\d+)')
        if (-not $match.Success) {
            return
        }
        $port = [int]$match.Groups[1].Value
        if ($port -lt 1 -or $port -gt 65535) {
            return
        }
        [Environment]::SetEnvironmentVariable(
            "LOCAL_CONTAINER_PROXY_URL",
            "http://host.docker.internal:$port",
            "Process"
        )
        Write-Host "[PROXY] Local container proxy: detected Windows loopback port $port"
    } catch {
        Write-Host "[PROXY] Local container proxy: not detected ($($_.Exception.Message))"
    }
}

function Test-LocalTcpListener($Port) {
    $Client = New-Object System.Net.Sockets.TcpClient
    try {
        $Result = $Client.BeginConnect("127.0.0.1", $Port, $null, $null)
        if (-not $Result.AsyncWaitHandle.WaitOne(350)) {
            return $false
        }
        $Client.EndConnect($Result)
        return $true
    } catch {
        return $false
    } finally {
        $Client.Dispose()
    }
}

function Assert-HostAppPortsAvailable {
    $Ports = @(
        @{ Port = 8080; Name = "Backend" },
        @{ Port = 8090; Name = "Agent Service" },
        @{ Port = 5173; Name = "User Web" },
        @{ Port = 5174; Name = "Admin Web" }
    )
    $Conflicts = @($Ports | Where-Object { Test-LocalTcpListener $_.Port })
    if ($Conflicts.Count -eq 0) {
        Write-Host "[OK] Host app ports are available"
        return
    }
    Write-Host "[ERROR] Host app ports are already occupied:"
    $Conflicts | ForEach-Object { Write-Host "  $($_.Name): $($_.Port)" }
    Write-Host "[INFO] This usually means the Docker app stack or another dev launcher is already running."
    Write-Host "[INFO] Inspect it with: docker compose -f deploy/docker-compose.yml -f deploy/docker-compose.ppt.yml ps"
    throw "Refusing to launch duplicate host services. Stop the existing app services, then rerun this script."
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
        Write-Host "[SKIP] Docker not found. Assuming local MySQL/RabbitMQ are already running."
        return
    }

    $UseRabbitMq = $env:TASK_QUEUE_BACKEND -and $env:TASK_QUEUE_BACKEND.Trim().ToLowerInvariant() -eq "rabbitmq"
    $InfraServices = @("mysql")
    if ($UseRabbitMq) {
        $InfraServices += "rabbitmq"
    } else {
        $InfraServices += "redis"
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
        $RedisReady = $UseRabbitMq
        if (-not $UseRabbitMq) {
            cmd /c "docker exec ai-supermarket-redis redis-cli ping >nul 2>nul"
            $RedisReady = $LASTEXITCODE -eq 0
        }
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

function Test-PptEnabled {
    if ($StartPpt -eq "1") {
        return $true
    }
    if ($StartPpt -eq "0") {
        return $false
    }
    return $env:PPT_WORKBENCH_ENABLED -and
        $env:PPT_WORKBENCH_ENABLED.Trim().ToLowerInvariant() -in @("1", "true", "yes", "on")
}

function Write-PptEngineFailureDiagnostics {
    Write-Host "[DIAG] PPT compose services:"
    & docker compose --env-file (Join-Path $Root ".env") `
        -f (Join-Path $Root "deploy\docker-compose.yml") `
        -f (Join-Path $Root "deploy\docker-compose.ppt.yml") `
        -f (Join-Path $Root "deploy\docker-compose.ppt.local.yml") `
        ps banana-slides banana-slides-migrate
    Write-Host "[DIAG] Banana migration log:"
    & docker logs --tail 80 deploy-banana-slides-migrate-1 2>&1
    Write-Host "[DIAG] Banana application log:"
    & docker logs --tail 80 ai-supermarket-banana-slides 2>&1
}

function Start-PptEngineIfNeeded {
    if (-not (Test-PptEnabled)) {
        Write-Host "[SKIP] PPT engine startup disabled"
        return
    }
    Require-Command "docker" "Docker Desktop"
    if (-not $env:BANANA_SLIDES_IMAGE) {
        throw "PPT is enabled but BANANA_SLIDES_IMAGE is missing from .env."
    }
    $PptDataDir = $env:BANANA_SLIDES_LOCAL_DATA_DIR
    foreach ($Directory in @(
        $PptDataDir,
        (Join-Path $PptDataDir "instance"),
        (Join-Path $PptDataDir "uploads")
    )) {
        if (-not (Test-Path -LiteralPath $Directory)) {
            New-Item -ItemType Directory -Path $Directory -Force | Out-Null
        }
    }
    Write-Host "[OK] Local PPT data: $PptDataDir"

    Write-Section "Starting local PPT engine"
    $ComposeFiles = @(
        (Join-Path $Root "deploy\docker-compose.yml"),
        (Join-Path $Root "deploy\docker-compose.ppt.yml"),
        (Join-Path $Root "deploy\docker-compose.ppt.local.yml")
    )
    $ComposeArgs = @("compose", "--env-file", (Join-Path $Root ".env"))
    foreach ($ComposeFile in $ComposeFiles) {
        $ComposeArgs += @("-f", $ComposeFile)
    }
    $ComposeArgs += @("up", "-d", "banana-slides")
    & docker @ComposeArgs
    if ($LASTEXITCODE -ne 0) {
        Write-PptEngineFailureDiagnostics
        throw "Failed to start Banana Slides. Review the compose, migration, and application diagnostics above."
    }

    $PptPort = if ($env:BANANA_SLIDES_LOCAL_PORT) { $env:BANANA_SLIDES_LOCAL_PORT } else { "5000" }
    $HealthUrl = "http://127.0.0.1:$PptPort/readyz"
    Write-Host "[..] Waiting for Banana Slides: $HealthUrl"
    if (-not (Test-HttpReady $HealthUrl 90)) {
        Write-PptEngineFailureDiagnostics
        throw "Banana Slides did not become ready at $HealthUrl within 90 seconds."
    }
    Write-Host "[OK] Banana Slides is ready"
}

function Write-PptDiagnostics {
    if (-not (Test-PptEnabled)) {
        return
    }
    Write-Section "PPT diagnostics"
    $PptPort = if ($env:BANANA_SLIDES_LOCAL_PORT) { $env:BANANA_SLIDES_LOCAL_PORT } else { "5000" }
    Write-Host "Host -> Banana:    http://127.0.0.1:$PptPort"
    Write-Host "Banana -> Backend: $env:PPT_MODEL_GATEWAY_BASE_URL"
    Write-Host "Platform assets:   $env:GENERATED_MEDIA_DIR"
    Write-Host "Banana instance:   $(Join-Path $env:BANANA_SLIDES_LOCAL_DATA_DIR 'instance')"
    Write-Host "Banana uploads:    $(Join-Path $env:BANANA_SLIDES_LOCAL_DATA_DIR 'uploads')"
    Write-Host "Banana exports:    $(Join-Path $env:BANANA_SLIDES_LOCAL_DATA_DIR 'uploads\<project-id>\exports')"
    try {
        $Runner = New-MysqlRunner
        if ($Runner) {
            $HasJobs = Invoke-MysqlScalar $Runner "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name='ppt_jobs';"
            if ($HasJobs -eq "1") {
                $Rows = Invoke-MysqlScalar $Runner "SELECT CONCAT(id,' | user=',user_id,' | project=',project_id,' | ',job_type,' | ',status,' | ',COALESCE(error_code,'-'),' | ',DATE_FORMAT(updated_at,'%Y-%m-%d %H:%i:%s')) FROM ppt_jobs ORDER BY id DESC LIMIT 1;"
                if ($Rows) {
                    Write-Host "Latest PPT job:    $Rows"
                } else {
                    Write-Host "Latest PPT job:    none"
                }
                $Active = Invoke-MysqlScalar $Runner "SELECT CONCAT('jobs=',COUNT(*),', users=',COUNT(DISTINCT user_id)) FROM ppt_jobs WHERE status IN ('CREATED','CREDIT_RESERVED','SUBMITTED','RUNNING','RECONCILING');"
                Write-Host "Active PPT work:   $Active"
                $LatestChild = Invoke-MysqlScalar $Runner "SELECT CONCAT('invocation=',i.id,', task=',t.id,', ',t.status,', queueWaitSec=',COALESCE(TIMESTAMPDIFF(SECOND,t.queued_at,t.started_at),0)) FROM ppt_model_invocations i JOIN ai_tasks t ON t.id=i.ai_task_id ORDER BY i.id DESC LIMIT 1;"
                if ($LatestChild) {
                    Write-Host "Latest model task: $LatestChild"
                }
                $PendingOutbox = Invoke-MysqlScalar $Runner "SELECT CONCAT('events=',COUNT(*),', oldestSec=',COALESCE(MAX(TIMESTAMPDIFF(SECOND,created_at,NOW())),0)) FROM task_outbox_events WHERE status='PENDING';"
                Write-Host "Pending outbox:    $PendingOutbox"
            } else {
                Write-Host "[WARN] ppt_jobs table is missing. Apply SQL migrations with -ApplySql 1."
            }
        }
    } catch {
        Write-Host "[WARN] Could not read PPT database diagnostics: $($_.Exception.Message)"
    }

    $RabbitNames = @(& docker ps --format "{{.Names}}" 2>$null)
    if ($LASTEXITCODE -eq 0 -and ($RabbitNames -contains "ai-supermarket-rabbitmq")) {
        Write-Host "RabbitMQ queues:"
        $PreviousErrorActionPreference = $ErrorActionPreference
        $ErrorActionPreference = "Continue"
        try {
            $QueueRows = @(& docker exec ai-supermarket-rabbitmq rabbitmqctl list_queues --timeout 5 name messages_ready messages_unacknowledged consumers 2>&1)
            $QueueExitCode = $LASTEXITCODE
        } finally {
            $ErrorActionPreference = $PreviousErrorActionPreference
        }
        if ($QueueExitCode -eq 0) {
            $QueueRows | Select-Object -First 12 | ForEach-Object { Write-Host "  $_" }
        } else {
            Write-Host "  [WARN] Queue diagnostics unavailable (exit $QueueExitCode)."
        }
    }
    Write-Host "Live engine logs:  docker logs -f ai-supermarket-banana-slides"
}

Import-DotEnv (Join-Path $Root ".env")
Set-LocalContainerProxyFromWindows

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
# Local dev now uses RabbitMQ as the task queue source of truth.
[Environment]::SetEnvironmentVariable("TASK_QUEUE_BACKEND", "rabbitmq", "Process")
Set-DefaultEnv "RABBITMQ_HOST" "127.0.0.1"
Set-DefaultEnv "RABBITMQ_PORT" "5672"
Set-DefaultEnv "RABBITMQ_USERNAME" "guest"
Set-DefaultEnv "RABBITMQ_PASSWORD" "guest"
Set-DefaultEnv "RABBITMQ_TASK_QUEUE" "ai.tool.normal"
Set-DefaultEnv "GENERATED_MEDIA_DIR" (Join-Path $Root "data\generated-media")
Set-DefaultEnv "GENERATED_MEDIA_PUBLIC_BASE_URL" "/generated"
Set-DefaultEnv "BANANA_SLIDES_LOCAL_PORT" "5000"
Set-DefaultEnv "BANANA_SLIDES_LOCAL_DATA_DIR" (Join-Path $Root ".local\banana-slides")
# This launcher runs the backend on the Windows host. Keep these addresses
# distinct from the production Docker-network values in .env/compose.
[Environment]::SetEnvironmentVariable(
    "PPT_ENGINE_BASE_URL",
    "http://127.0.0.1:$env:BANANA_SLIDES_LOCAL_PORT",
    "Process"
)
[Environment]::SetEnvironmentVariable(
    "PPT_MODEL_GATEWAY_BASE_URL",
    "http://host.docker.internal:8080",
    "Process"
)

Write-Section "AI Tool Market - Local Dev Launcher"
Write-Host "Root:          $Root"
try {
    $GitBranch = (& git -c safe.directory=$($Root.Replace("\", "/")) -C $Root branch --show-current 2>$null).Trim()
} catch {
    $GitBranch = "(unavailable)"
}
Write-Host "Git branch:    $GitBranch"
Write-Host "Infra mode:    $StartInfra"
Write-Host "PPT mode:      $StartPpt"
Write-Host "SQL check:     $ApplySql"
Write-Host "Backend:       http://localhost:8080"
Write-Host "Agent Service: http://localhost:8090"
Write-Host "User Web:      http://localhost:5173"
Write-Host "Admin Web:     http://localhost:5174"
Write-Host "Queue backend: $env:TASK_QUEUE_BACKEND"
Write-Host "Worker:        $WorkerCount x $env:TASK_QUEUE_BACKEND queue consumers"

Start-InfraIfNeeded
Apply-LocalSqlMigrations
Start-PptEngineIfNeeded
Write-PptDiagnostics

if ($PreflightOnly) {
    Write-Section "Preflight complete"
    Write-Host "No host application process was started (-PreflightOnly)."
    exit 0
}

Assert-HostAppPortsAvailable
Require-Command "java" "JDK 17+"
Require-Command "mvn" "Maven 3.8+"
Require-Command "npm" "Node.js/npm"
if (-not (Test-Path -LiteralPath $VenvPython)) {
    Require-Command "python" "Python 3.11+"
}

Install-NodeDeps "user-web"
Install-NodeDeps "admin-frontend"
Sync-PythonVenvDeps

Write-Section "Starting app services on host"
Start-DevWindow "Backend :8080" "backend" "mvn spring-boot:run"

Write-Host "[..] Waiting for backend health..."
if (Test-HttpReady "http://localhost:8080/api/health" 80) {
    Write-Host "[OK] Backend is ready"
} else {
    Write-Host "[WARN] Backend did not answer health check yet. Check the Backend window."
}

Start-DevWindow "Agent Service :8090" "agent-service" "& '$($VenvPython.Replace("'", "''"))' -m uvicorn app.main:app --reload --port 8090"

Write-Host "[..] Waiting for agent-service health..."
if (Test-HttpReady "http://localhost:8090/health" 40) {
    Write-Host "[OK] Agent Service is ready"
} else {
    Write-Host "[WARN] Agent Service did not answer health check yet. Check the Agent Service window."
}

for ($WorkerIndex = 1; $WorkerIndex -le $WorkerCount; $WorkerIndex++) {
    Start-DevWindow "Worker Queue #$WorkerIndex" "worker" "& '$($VenvPython.Replace("'", "''"))' main.py"
}
Start-DevWindow "User Web :5173" "user-web" "npm run dev"
Start-DevWindow "Admin Web :5174" "admin-frontend" "npm run dev"

Write-Section "Local dev services launched"
Write-Host "Backend API:     http://localhost:8080"
Write-Host "Backend health:  http://localhost:8080/api/health"
Write-Host "Agent health:    http://localhost:8090/health"
Write-Host "User Web:        http://localhost:5173"
Write-Host "Admin Web:       http://localhost:5174"
if (Test-PptEnabled) {
    Write-Host "Banana readiness:http://127.0.0.1:$env:BANANA_SLIDES_LOCAL_PORT/readyz"
    Write-Host "PPT engine logs: docker logs -f ai-supermarket-banana-slides"
}
Write-Host ""
Write-Host "Close each opened terminal window to stop that service."
