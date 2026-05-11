param(
    [string]$BackendBaseUrl = "http://127.0.0.1:8080",
    [string]$Username = "agent_smoke_load_user",
    [string]$Password = "123456",
    [int]$Requests = 8,
    [int]$DelayMilliseconds = 250,
    [int]$ReadyAttempts = 30,
    [switch]$DryRun
)

$ErrorActionPreference = "Stop"

function Join-ApiUrl {
    param([string]$Path)
    return $BackendBaseUrl.TrimEnd("/") + $Path
}

function Invoke-AgentApi {
    param(
        [ValidateSet("GET", "POST")]
        [string]$Method,
        [string]$Path,
        [object]$Body = $null,
        [string]$Token = $null,
        [switch]$AllowFailure
    )

    $headers = @{}
    if ($Token) {
        $headers["Authorization"] = "Bearer $Token"
    }

    $params = @{
        Method = $Method
        Uri = Join-ApiUrl $Path
        Headers = $headers
        ContentType = "application/json"
        TimeoutSec = 15
    }
    if ($null -ne $Body) {
        $params["Body"] = ($Body | ConvertTo-Json -Depth 12)
    }

    try {
        $response = Invoke-RestMethod @params
        if ($response.code -and $response.code -ne "SUCCESS" -and -not $AllowFailure) {
            throw "API $Method $Path failed: $($response.code) $($response.message)"
        }
        return $response
    } catch {
        $detail = Get-HttpErrorDetail -ErrorRecord $_
        if ($AllowFailure) {
            return [pscustomobject]@{
                code = "HTTP_ERROR"
                message = $detail
                data = $null
            }
        }
        throw "API $Method $Path failed. url=$($params.Uri) $detail"
    }
}

function Get-HttpErrorDetail {
    param([System.Management.Automation.ErrorRecord]$ErrorRecord)

    $message = $ErrorRecord.Exception.Message
    $status = $null
    if ($null -ne $ErrorRecord.Exception.Response) {
        try {
            $status = [int]$ErrorRecord.Exception.Response.StatusCode
        } catch {
            $status = $ErrorRecord.Exception.Response.StatusCode
        }
    }

    if ($status) {
        return "status=$status message=$message"
    }

    return "message=$message"
}

function Invoke-AgentApiWithRetry {
    param(
        [ValidateSet("GET", "POST")]
        [string]$Method,
        [string]$Path,
        [object]$Body = $null,
        [string]$Token = $null,
        [int]$Attempts = 30,
        [int]$DelaySeconds = 1,
        [string]$Operation = "$Method $Path"
    )

    $lastError = $null
    for ($attempt = 1; $attempt -le $Attempts; $attempt++) {
        try {
            return Invoke-AgentApi -Method $Method -Path $Path -Body $Body -Token $Token
        } catch {
            $lastError = $_.Exception.Message
            if ($attempt -ge $Attempts) {
                break
            }

            Write-Host "Service not ready for $Operation, retry $attempt/${Attempts}: $lastError"
            Start-Sleep -Seconds $DelaySeconds
        }
    }

    throw "Service did not become ready for $Operation after $Attempts attempts. lastError=$lastError"
}

function Get-OrCreateToken {
    try {
        $login = Invoke-AgentApiWithRetry -Method POST -Path "/api/v1/auth/login" -Operation "login/backend readiness" -Attempts $ReadyAttempts -Body @{
            account = $Username
            password = $Password
        }
        return $login.data.accessToken
    } catch {
        Write-Host "Login failed, registering smoke load user: $Username"
        $register = Invoke-AgentApi -Method POST -Path "/api/v1/auth/register" -Body @{
            username = $Username
            password = $Password
            nickname = "Agent Load Smoke"
        }
        return $register.data.accessToken
    }
}

if ($DryRun) {
    Write-Host "DRY RUN: would send $Requests Agent messages to $BackendBaseUrl"
    exit 0
}

$token = Get-OrCreateToken
$sessionResponse = Invoke-AgentApi -Method POST -Path "/api/v1/agent/sessions" -Token $token -Body @{
    title = "Agent smoke load"
}
$sessionId = $sessionResponse.data.id

$accepted = 0
$limited = 0
$businessFailures = 0
$httpFailures = 0
$runIds = @()
$failureDetails = @()

for ($i = 1; $i -le $Requests; $i++) {
    $response = Invoke-AgentApi -Method POST -Path "/api/v1/agent/sessions/$sessionId/messages" -Token $token -AllowFailure -Body @{
        content = "Smoke load request $i. Recommend a tool briefly."
        clientRequestId = [guid]::NewGuid().ToString()
    }

    switch ($response.code) {
        "SUCCESS" {
            $accepted++
            $runIds += $response.data.runId
            Write-Host "request $i accepted: runId=$($response.data.runId)"
        }
        "AGENT_RATE_LIMITED" {
            $limited++
            Write-Host "request $i rate limited"
        }
        "AGENT_RUN_LIMIT_EXCEEDED" {
            $limited++
            Write-Host "request $i active-run limited"
        }
        default {
            $businessFailures++
            if ($response.code -eq "HTTP_ERROR") {
                $httpFailures++
            }
            $failureDetails += "request=$i code=$($response.code) message=$($response.message)"
            Write-Host "request $i failed: $($response.code) $($response.message)"
        }
    }

    Start-Sleep -Milliseconds $DelayMilliseconds
}

if ($accepted -lt 1) {
    throw "Smoke load did not get any accepted Agent request. limited=$limited failures=$businessFailures httpFailures=$httpFailures details=$($failureDetails -join ' | ')"
}

Write-Host "acceptedRunIds=$($runIds -join ', ')"
if ($failureDetails.Count -gt 0) {
    Write-Host "failureDetails=$($failureDetails -join ' | ')"
} else {
    Write-Host "failureDetails=none"
}
Write-Host "PASS: agent smoke load accepted=$accepted limited=$limited failures=$businessFailures httpFailures=$httpFailures"
