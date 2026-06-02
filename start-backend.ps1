$ErrorActionPreference = "Stop"

$Root = Split-Path -Parent $MyInvocation.MyCommand.Path
$LogDir = Join-Path $Root "logs"
New-Item -ItemType Directory -Force -Path $LogDir | Out-Null

$EnvFile = Join-Path $Root ".env"
if (Test-Path $EnvFile) {
    Get-Content $EnvFile | Where-Object { $_ -match '^\s*[^#].*=' } | ForEach-Object {
        $parts = $_ -split '=', 2
        $name = $parts[0].Trim()
        $value = $parts[1].Trim()
        if ($name) {
            Set-Item -Path "Env:$name" -Value $value
        }
    }
}

$env:DB_HOST = if ($env:DB_HOST) { $env:DB_HOST } else { "localhost" }
$env:DB_PORT = if ($env:DB_PORT) { $env:DB_PORT } else { "3306" }
$env:DB_NAME = if ($env:DB_NAME) { $env:DB_NAME } else { "multi-agent-demo" }
$env:DB_USERNAME = if ($env:DB_USERNAME) { $env:DB_USERNAME } else { "multi_agent_demo" }
$env:DB_PASSWORD = if ($env:DB_PASSWORD) { $env:DB_PASSWORD } else { "change-me-db-password" }
$env:NACOS_SERVER_ADDR = if ($env:NACOS_SERVER_ADDR) { $env:NACOS_SERVER_ADDR } else { "127.0.0.1:8848" }
$env:NACOS_USERNAME = if ($env:NACOS_USERNAME) { $env:NACOS_USERNAME } else { "nacos" }
$env:NACOS_PASSWORD = if ($env:NACOS_PASSWORD) { $env:NACOS_PASSWORD } else { "change-me-nacos-password" }
$env:NACOS_NAMESPACE = if ($env:NACOS_NAMESPACE) { $env:NACOS_NAMESPACE } else { "public" }
$env:NACOS_REGISTER_ENABLED = if ($env:NACOS_REGISTER_ENABLED) { $env:NACOS_REGISTER_ENABLED } else { "true" }
$env:NACOS_CLIENT_ENABLED = if ($env:NACOS_CLIENT_ENABLED) { $env:NACOS_CLIENT_ENABLED } else { "true" }
$env:MEM0_ADDRESS = if ($env:MEM0_ADDRESS) { $env:MEM0_ADDRESS } else { "https://api.mem0.ai" }
$env:DASHSCOPE_MODEL = if ($env:DASHSCOPE_MODEL) { $env:DASHSCOPE_MODEL } else { "qwen-plus" }
$env:DASHSCOPE_ENABLE_RERANKING = if ($env:DASHSCOPE_ENABLE_RERANKING) { $env:DASHSCOPE_ENABLE_RERANKING } else { "true" }
$env:DASHSCOPE_RERANK_TOP_N = if ($env:DASHSCOPE_RERANK_TOP_N) { $env:DASHSCOPE_RERANK_TOP_N } else { "2" }
$env:DASHSCOPE_RERANK_MIN_SCORE = if ($env:DASHSCOPE_RERANK_MIN_SCORE) { $env:DASHSCOPE_RERANK_MIN_SCORE } else { "0" }
$env:XXL_JOB_ENABLED = if ($env:XXL_JOB_ENABLED) { $env:XXL_JOB_ENABLED } else { "false" }

if (-not $env:DASHSCOPE_API_KEY -or -not $env:DASHSCOPE_INDEX_ID -or -not $env:MEM0_API_KEY) {
    Write-Warning "Missing AI environment variables. Please copy env.template to .env and fill DASHSCOPE_API_KEY, DASHSCOPE_INDEX_ID and MEM0_API_KEY."
}

$Services = @(
    @{ Name = "feedback-mcp-server"; Dir = "feedback-mcp-server"; Jar = "feedback-mcp-server-1.0.0.jar"; Port = 10004 },
    @{ Name = "order-mcp-server"; Dir = "order-mcp-server"; Jar = "order-mcp-server-1.0.0.jar"; Port = 10002 },
    @{ Name = "memory-mcp-server"; Dir = "memory-mcp-server"; Jar = "memory-mcp-server-1.0.0.jar"; Port = 10010 },
    @{ Name = "feedback-sub-agent"; Dir = "feedback-sub-agent"; Jar = "feedback-sub-agent-1.0.0.jar"; Port = 10007 },
    @{ Name = "consult-sub-agent"; Dir = "consult-sub-agent"; Jar = "consult-sub-agent-1.0.0.jar"; Port = 10005 },
    @{ Name = "order-sub-agent"; Dir = "order-sub-agent"; Jar = "order-sub-agent-1.0.0.jar"; Port = 10006 },
    @{ Name = "supervisor-agent"; Dir = "supervisor-agent"; Jar = "supervisor-agent-1.0.0.jar"; Port = 10008 }
)

function Wait-Port {
    param(
        [int] $Port,
        [string] $Name,
        [int] $TimeoutSeconds = 60
    )

    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    while ((Get-Date) -lt $deadline) {
        $connection = Get-NetTCPConnection -LocalPort $Port -State Listen -ErrorAction SilentlyContinue
        if ($connection) {
            Write-Host "[OK] $Name listening on $Port"
            return $true
        }
        Start-Sleep -Seconds 2
    }

    Write-Warning "$Name did not listen on $Port within $TimeoutSeconds seconds. Check logs\$Name.err.log and logs\$Name.out.log"
    return $false
}

foreach ($svc in $Services) {
    $existing = Get-NetTCPConnection -LocalPort $svc.Port -State Listen -ErrorAction SilentlyContinue
    if ($existing) {
        Write-Host "[SKIP] $($svc.Name) port $($svc.Port) is already listening"
        continue
    }

    $serviceDir = Join-Path $Root $svc.Dir
    $jarPath = Join-Path $serviceDir "target\$($svc.Jar)"
    if (!(Test-Path $jarPath)) {
        throw "Jar not found: $jarPath. Run: mvn clean package -DskipTests `"-Dmaven.repo.local=.m2\repository`""
    }

    $outLog = Join-Path $LogDir "$($svc.Name).out.log"
    $errLog = Join-Path $LogDir "$($svc.Name).err.log"
    $pidFile = Join-Path $LogDir "$($svc.Name).pid"

    $proc = Start-Process -FilePath "java" `
        -ArgumentList @("-jar", $jarPath) `
        -WorkingDirectory $serviceDir `
        -RedirectStandardOutput $outLog `
        -RedirectStandardError $errLog `
        -WindowStyle Hidden `
        -PassThru

    $proc.Id | Set-Content -Path $pidFile
    Write-Host "[START] $($svc.Name) pid=$($proc.Id), port=$($svc.Port)"
    Wait-Port -Port $svc.Port -Name $svc.Name | Out-Null
}

Write-Host ""
Write-Host "Backend services started. Main API: http://localhost:10008"
