param(
    [string]$BaseUrl = "http://127.0.0.1:8080",
    [string]$Username = "user1",
    [string]$Password = "123456",
    [int]$ReadyAttempts = 30,
    [int]$PollAttempts = 30,
    [int]$PollDelaySeconds = 1
)

$ErrorActionPreference = "Stop"

function Join-ApiUrl {
    param([string]$Path)
    return $BaseUrl.TrimEnd("/") + $Path
}

function Invoke-AgentJson {
    param(
        [ValidateSet("GET", "POST")]
        [string]$Method,
        [string]$Path,
        [object]$Body = $null,
        [string]$Token = $null
    )

    $headers = @{}
    if (-not [string]::IsNullOrWhiteSpace($Token)) {
        $headers["Authorization"] = "Bearer $Token"
    }

    $request = @{
        Method = $Method
        Uri = Join-ApiUrl $Path
        Headers = $headers
        ContentType = "application/json"
        TimeoutSec = 15
    }

    if ($null -ne $Body) {
        $request["Body"] = $Body | ConvertTo-Json -Depth 10
    }

    try {
        return Invoke-RestMethod @request
    } catch {
        $detail = Get-HttpErrorDetail -ErrorRecord $_
        throw "API $Method $Path failed. url=$($request.Uri) $detail"
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

function Invoke-AgentJsonWithRetry {
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
            return Invoke-AgentJson -Method $Method -Path $Path -Body $Body -Token $Token
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

function Get-ResponseData {
    param([object]$Response)

    if ($null -ne $Response -and $Response.PSObject.Properties.Name -contains "data") {
        return $Response.data
    }

    return $Response
}

function Get-RequiredProperty {
    param(
        [object]$Value,
        [string[]]$Names,
        [string]$Description
    )

    foreach ($name in $Names) {
        if ($null -ne $Value -and $Value.PSObject.Properties.Name -contains $name) {
            $propertyValue = $Value.$name
            if ($null -ne $propertyValue -and -not [string]::IsNullOrWhiteSpace([string]$propertyValue)) {
                return $propertyValue
            }
        }
    }

    throw "Missing $Description. Tried properties: $($Names -join ', ')"
}

function Get-EventType {
    param([object]$Event)
    return Get-RequiredProperty -Value $Event -Names @("eventType", "type") -Description "event type"
}

function Get-EventId {
    param([object]$Event)

    foreach ($name in @("id", "eventId")) {
        if ($null -ne $Event -and $Event.PSObject.Properties.Name -contains $name) {
            return [string]$Event.$name
        }
    }

    return ""
}

function Get-EventSummary {
    param([object[]]$Events)

    return $Events | ForEach-Object {
        $eventType = Get-EventType $_
        $eventId = Get-EventId $_
        if (-not [string]::IsNullOrWhiteSpace($eventId)) {
            return "$eventType#$eventId"
        }
        return $eventType
    }
}

function Assert-ExpectedLifecycle {
    param([object[]]$Events)

    $expected = @("run.started", "intent.detected", "message.delta", "message.completed", "run.completed")
    $eventTypes = @($Events | ForEach-Object { Get-EventType $_ })
    $positions = @()

    foreach ($expectedType in $expected) {
        $index = [array]::IndexOf($eventTypes, $expectedType)
        if ($index -lt 0) {
            throw "Missing expected event '$expectedType'. expected=$($expected -join ' -> ') actual=$($eventTypes -join ' -> ')"
        }
        $positions += $index
    }

    for ($i = 1; $i -lt $positions.Count; $i++) {
        if ($positions[$i] -le $positions[$i - 1]) {
            throw "Expected lifecycle order was not preserved. expected=$($expected -join ' -> ') actual=$($eventTypes -join ' -> ')"
        }
    }

    $duplicates = $eventTypes |
        Group-Object |
        Where-Object { $_.Count -gt 1 -and $_.Name -in @("run.started", "intent.detected", "message.completed", "run.completed") }

    return [pscustomobject]@{
        Expected = $expected
        Actual = $eventTypes
        DuplicateLifecycleEvents = @($duplicates | ForEach-Object { "$($_.Name)x$($_.Count)" })
    }
}

$loginResponse = Invoke-AgentJsonWithRetry -Method POST -Path "/api/v1/auth/login" -Operation "login/backend readiness" -Attempts $ReadyAttempts -Body @{
    account = $Username
    password = $Password
}
$loginData = Get-ResponseData $loginResponse
$token = Get-RequiredProperty -Value $loginData -Names @("token", "accessToken") -Description "login token"

$sessionResponse = Invoke-AgentJson -Method POST -Path "/api/v1/agent/sessions" -Token $token -Body @{
    title = "Agent Smoke"
}
$sessionData = Get-ResponseData $sessionResponse
$sessionId = Get-RequiredProperty -Value $sessionData -Names @("sessionId", "id") -Description "session id"

$smokePrompt = -join ([char[]](
    0x5E2E, 0x6211, 0x7ED9, 0x62A4, 0x80A4, 0x5957, 0x88C5,
    0x5199, 0x4E00, 0x7BC7, 0x5C0F, 0x7EA2, 0x4E66, 0x79CD,
    0x8349, 0x6587, 0x6848
))

$messageResponse = Invoke-AgentJson -Method POST -Path "/api/v1/agent/sessions/$sessionId/messages" -Token $token -Body @{
    content = $smokePrompt
}
$messageData = Get-ResponseData $messageResponse
$runId = Get-RequiredProperty -Value $messageData -Names @("runId", "id") -Description "run id"

$allEvents = @()
$successfulEvent = $null

for ($attempt = 1; $attempt -le $PollAttempts; $attempt++) {
    $eventsResponse = Invoke-AgentJson -Method GET -Path "/api/v1/agent/runs/$runId/events?pageSize=200" -Token $token
    $eventsData = Get-ResponseData $eventsResponse

    $events = @()
    if ($null -ne $eventsData -and $eventsData.PSObject.Properties.Name -contains "list") {
        $events = @($eventsData.list)
    } elseif ($null -ne $eventsData -and $eventsData -is [array]) {
        $events = @($eventsData)
    }

    $allEvents = $events
    foreach ($event in $events) {
        $eventType = Get-EventType $event

        if ($eventType -eq "run.failed") {
            $eventSummary = Get-EventSummary $events
            throw "Agent run failed. sessionId=$sessionId runId=$runId events=$($eventSummary -join ', ')"
        }

        if ($eventType -eq "run.completed") {
            $successfulEvent = $eventType
            break
        }
    }

    if ($successfulEvent) {
        break
    }

    Write-Host "Waiting for run.completed attempt $attempt/$PollAttempts events=$($events.Count)"
    Start-Sleep -Seconds $PollDelaySeconds
}

if ($allEvents.Count -eq 0) {
    throw "No run events were returned. sessionId=$sessionId runId=$runId"
}

if (-not $successfulEvent) {
    $eventTypes = $allEvents | ForEach-Object { Get-EventType $_ }
    throw "Agent run did not reach run.completed. sessionId=$sessionId runId=$runId events=$($eventTypes -join ', ')"
}

$lifecycle = Assert-ExpectedLifecycle -Events $allEvents
$eventSummary = Get-EventSummary $allEvents

Write-Host "sessionId=$sessionId"
Write-Host "runId=$runId"
Write-Host "expectedLifecycle=$($lifecycle.Expected -join ' -> ')"
Write-Host "actualLifecycle=$($lifecycle.Actual -join ' -> ')"
if ($lifecycle.DuplicateLifecycleEvents.Count -gt 0) {
    throw "Duplicate lifecycle events detected: $($lifecycle.DuplicateLifecycleEvents -join ', ')"
}
Write-Host "duplicateLifecycleEvents=none"
Write-Host "events=$($eventSummary -join ', ')"
Write-Host "PASS: agent e2e smoke completed without duplicate lifecycle events"
