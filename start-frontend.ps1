$ErrorActionPreference = "Stop"

$Root = Split-Path -Parent $MyInvocation.MyCommand.Path
$FrontendDir = Join-Path $Root "frontend"

if (!(Test-Path (Join-Path $FrontendDir "package.json"))) {
    throw "package.json not found in $FrontendDir"
}

Push-Location $FrontendDir
try {
    if (!(Test-Path "node_modules")) {
        Write-Host "[INSTALL] frontend dependencies"
        npm.cmd install --legacy-peer-deps
        if ($LASTEXITCODE -ne 0) {
            throw "npm install failed with exit code $LASTEXITCODE"
        }
    }

    Write-Host "[START] frontend on http://localhost:3000"
    npm.cmd run dev -- --port 3000 --host 0.0.0.0
    if ($LASTEXITCODE -ne 0) {
        throw "npm run dev failed with exit code $LASTEXITCODE"
    }
}
finally {
    Pop-Location
}
