$ErrorActionPreference = "Stop"

$Root = Split-Path -Parent $MyInvocation.MyCommand.Path
$ProxyDir = Join-Path $Root "domain-proxy"

Push-Location $ProxyDir
try {
    docker-compose up -d
    docker-compose ps
}
finally {
    Pop-Location
}

Write-Host ""
Write-Host "Domain proxy started:"
Write-Host "  http://localhost"
Write-Host "If you want to use a custom domain, point the domain to this host and update domain-proxy/nginx.conf if needed."
