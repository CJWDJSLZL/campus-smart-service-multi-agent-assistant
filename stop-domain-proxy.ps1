$ErrorActionPreference = "Stop"

$Root = Split-Path -Parent $MyInvocation.MyCommand.Path
$ProxyDir = Join-Path $Root "domain-proxy"

Push-Location $ProxyDir
try {
    docker-compose down
}
finally {
    Pop-Location
}
