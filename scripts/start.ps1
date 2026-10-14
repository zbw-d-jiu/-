param([switch]$Demo, [switch]$NoBrowser)
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'tooling.ps1')
Set-Location $Script:ProjectRoot
$taskData = Join-Path $Script:ProjectRoot '.data'
New-Item -ItemType Directory -Path $taskData -Force | Out-Null
$taskJar = Join-Path $Script:ProjectRoot 'backend\target\study-room-0.3.0.jar'
if (-not (Test-Path -LiteralPath $taskJar) -or -not (Test-Path -LiteralPath 'frontend\node_modules\vite\bin\vite.js')) { throw '请先执行build.bat。' }
$taskJava = Get-Command java -ErrorAction SilentlyContinue
if (-not $taskJava) { throw '请安装JDK21并将java加入PATH。' }

if (-not $Demo) {
    # 默认连接真实MySQL。可显式提供外部DB_*，不会覆盖用户的现有连接配置。
    if (-not $env:DB_URL) {
        $taskEnvFile = Join-Path $taskData 'mysql.env.ps1'
        if (-not (Test-Path -LiteralPath $taskEnvFile)) { throw '配置DB_URL/DB_USERNAME/DB_PASSWORD，或先执行scripts/start-local-mysql.ps1。' }
        . $taskEnvFile
    }
}

function Test-ProjectListener {
    param([int]$Port,[string]$Expected,[string]$ServiceName)
    $taskListener = Get-NetTCPConnection -State Listen -LocalPort $Port -ErrorAction SilentlyContinue | Select-Object -First 1
    if (-not $taskListener) { return $false }
    $taskProcess = Get-CimInstance Win32_Process -Filter ('ProcessId=' + $taskListener.OwningProcess)
    $taskOwnedPidFile = Join-Path $taskData ($ServiceName + '.pid')
    $taskOwnedPid = if (Test-Path -LiteralPath $taskOwnedPidFile) { [int](Get-Content -LiteralPath $taskOwnedPidFile) } else { -1 }
    $taskKnownRoot = $taskProcess.CommandLine -and $taskProcess.CommandLine.Contains($Script:ProjectRoot)
    $taskKnownPid = $taskListener.OwningProcess -eq $taskOwnedPid -or $taskProcess.ParentProcessId -eq $taskOwnedPid
    if (-not $taskProcess.CommandLine -or -not $taskProcess.CommandLine.Contains($Expected) -or (-not $taskKnownRoot -and -not $taskKnownPid)) { throw ('端口' + $Port + '已被其他程序占用，不会停止该程序。') }
    return $true
}

# 后台隐藏启动，每个服务的PID与日志明确归本项目；不触碰其他Java/Node进程。
if (-not (Test-ProjectListener 8080 'study-room-0.3.0.jar' 'backend')) {
    $taskArgs = @('-jar',('"' + $taskJar + '"'),'--server.address=127.0.0.1')
    if ($Demo) { $taskArgs += '--spring.profiles.active=demo' }
    $taskBackend = Start-Process -FilePath $taskJava.Source -ArgumentList $taskArgs -WindowStyle Hidden -PassThru -RedirectStandardOutput (Join-Path $taskData 'backend.stdout.log') -RedirectStandardError (Join-Path $taskData 'backend.stderr.log')
    $taskBackend.Id | Set-Content -LiteralPath (Join-Path $taskData 'backend.pid')
}
if (-not (Test-ProjectListener 5173 'vite' 'frontend')) {
    $taskNode = Get-ProjectNode
    $taskViteEntry = Join-Path $Script:ProjectRoot 'frontend\node_modules\vite\bin\vite.js'
    $taskFrontend = Start-Process -FilePath $taskNode -ArgumentList ('"' + $taskViteEntry + '"'),'--host','127.0.0.1' -WorkingDirectory (Join-Path $Script:ProjectRoot 'frontend') -WindowStyle Hidden -PassThru -RedirectStandardOutput (Join-Path $taskData 'frontend.stdout.log') -RedirectStandardError (Join-Path $taskData 'frontend.stderr.log')
    $taskFrontend.Id | Set-Content -LiteralPath (Join-Path $taskData 'frontend.pid')
}
for ($taskWait = 0; $taskWait -lt 60; $taskWait++) {
    try { $taskHealth = Invoke-RestMethod 'http://127.0.0.1:8080/api/health'; if ($taskHealth.success) { break } } catch { }
    Start-Sleep -Milliseconds 500
}
if (-not $taskHealth -or -not $taskHealth.success) { throw '后端健康检查失败，请查看.data/backend.stdout.log和stderr.log。' }
Write-Host '系统已启动：http://127.0.0.1:5173 （后端8080，仅本机）'
if ($Demo) { Write-Host '当前为H2演示profile，非MySQL验证；停止后内存数据清空。' }
if (-not $NoBrowser) { Start-Process 'http://127.0.0.1:5173' }
