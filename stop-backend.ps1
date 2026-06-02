$ErrorActionPreference = "Continue"

$Root = Split-Path -Parent $MyInvocation.MyCommand.Path
$LogDir = Join-Path $Root "logs"

$Services = @(
    "supervisor-agent",
    "order-sub-agent",
    "consult-sub-agent",
    "feedback-sub-agent",
    "memory-mcp-server",
    "order-mcp-server",
    "feedback-mcp-server"
)

foreach ($name in $Services) {
    $pidFile = Join-Path $LogDir "$name.pid"
    if (!(Test-Path $pidFile)) {
        Write-Host "[SKIP] $name pid file not found"
        continue
    }

    $pidValue = (Get-Content $pidFile -ErrorAction SilentlyContinue | Select-Object -First 1).Trim()
    if (!$pidValue) {
        Remove-Item $pidFile -Force
        continue
    }

    $proc = Get-Process -Id ([int]$pidValue) -ErrorAction SilentlyContinue
    if ($proc) {
        Write-Host "[STOP] $name pid=$pidValue"
        Stop-Process -Id ([int]$pidValue) -Force
    } else {
        Write-Host "[SKIP] $name process $pidValue is not running"
    }

    Remove-Item $pidFile -Force
}

Write-Host "Backend services stopped."
