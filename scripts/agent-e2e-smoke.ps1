param(
    [string]$BaseUrl = "http://127.0.0.1:8080",
    [string]$Username = "user1",
    [string]$Password = "123456"
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

    return Invoke-RestMethod @request
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

$loginResponse = Invoke-AgentJson -Method POST -Path "/api/v1/auth/login" -Body @{
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

for ($attempt = 1; $attempt -le 20; $attempt++) {
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
        $eventType = Get-RequiredProperty -Value $event -Names @("eventType", "type") -Description "event type"

        if ($eventType -eq "run.failed") {
            throw "Agent run failed. sessionId=$sessionId runId=$runId"
        }

        if ($eventType -eq "tool.confirmation_required" -or $eventType -eq "run.completed") {
            $successfulEvent = $eventType
            break
        }
    }

    if ($successfulEvent) {
        break
    }

    Start-Sleep -Seconds 1
}

if ($allEvents.Count -eq 0) {
    throw "No run events were returned. sessionId=$sessionId runId=$runId"
}

if (-not $successfulEvent) {
    $eventTypes = $allEvents | ForEach-Object {
        Get-RequiredProperty -Value $_ -Names @("eventType", "type") -Description "event type"
    }
    throw "Agent run did not reach tool.confirmation_required or run.completed. sessionId=$sessionId runId=$runId events=$($eventTypes -join ', ')"
}

$eventSummary = $allEvents | ForEach-Object {
    $eventType = Get-RequiredProperty -Value $_ -Names @("eventType", "type") -Description "event type"
    if ($_.PSObject.Properties.Name -contains "id") {
        return "$eventType#$($_.id)"
    }
    return $eventType
}

Write-Host "sessionId=$sessionId"
Write-Host "runId=$runId"
Write-Host "events=$($eventSummary -join ', ')"
