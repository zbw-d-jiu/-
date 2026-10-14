param([int]$Port = 3307)
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'tooling.ps1')
$taskDataRoot = Join-Path $Script:ProjectRoot '.data'
New-Item -ItemType Directory -Path $taskDataRoot -Force | Out-Null
$taskAscii = Get-AsciiWorkspace
$taskMysqlBase = Join-Path $taskAscii '.tools\mysql-8.0.44-winx64'
$taskMysqlExe = Join-Path $taskMysqlBase 'bin\mysqld.exe'
if (-not (Test-Path -LiteralPath $taskMysqlExe)) { throw '请先执行scripts/bootstrap.ps1 -WithMySql。' }
$taskMysqlData = Join-Path $taskAscii '.data\mysql'
$taskEnvironment = Join-Path $taskDataRoot 'mysql.env.ps1'

# 已监听时只接纳本项目拥有的MySQL，绝不接管或停止用户原有服务。
$taskListener = Get-NetTCPConnection -State Listen -LocalPort $Port -ErrorAction SilentlyContinue | Select-Object -First 1
if ($taskListener) {
    $taskOwner = Get-CimInstance Win32_Process -Filter ('ProcessId=' + $taskListener.OwningProcess)
    $taskExpectedFragments = @($Script:ProjectRoot, $taskAscii, (Join-Path $env:USERPROFILE '.cache\study-room-dev'))
    $taskOurs = $false
    foreach ($taskFragment in $taskExpectedFragments) {
        if ($taskOwner.CommandLine -and $taskOwner.CommandLine.Contains($taskFragment)) { $taskOurs = $true }
    }
    if (-not $taskOurs -or -not (Test-Path -LiteralPath $taskEnvironment)) { throw ('端口' + $Port + '已被其他服务占用，请配置已有数据库或使用不同端口。') }
    Write-Host ('本项目MySQL已运行：127.0.0.1:' + $Port)
    exit 0
}

# 仅首次空目录初始化，严禁对既有数据目录执行清理/重新初始化。
if (-not (Test-Path -LiteralPath $taskMysqlData)) {
    & $taskMysqlExe --initialize-insecure ('--basedir=' + $taskMysqlBase) ('--datadir=' + $taskMysqlData)
    if ($LASTEXITCODE -ne 0) { throw 'MySQL独立数据目录初始化失败。' }
}
$taskConfig = Join-Path $taskAscii '.data\mysql-local.ini'
$taskIni = "[mysqld]`nbasedir=$($taskMysqlBase.Replace('\','/'))`ndatadir=$($taskMysqlData.Replace('\','/'))`nport=$Port`nbind-address=127.0.0.1`nmysqlx=0`ncharacter-set-server=utf8mb4`ncollation-server=utf8mb4_0900_ai_ci`n"
[System.IO.File]::WriteAllText($taskConfig, $taskIni, [System.Text.UTF8Encoding]::new($false))
$taskHandle = Start-Process -FilePath $taskMysqlExe -ArgumentList ('--defaults-file=' + $taskConfig),'--console' -WindowStyle Hidden -PassThru -RedirectStandardOutput (Join-Path $taskDataRoot 'mysql.stdout.log') -RedirectStandardError (Join-Path $taskDataRoot 'mysql.stderr.log')
$taskHandle.Id | Set-Content -LiteralPath (Join-Path $taskDataRoot 'mysql.pid')
for ($taskWait = 0; $taskWait -lt 40; $taskWait++) {
    if (Get-NetTCPConnection -State Listen -LocalPort $Port -ErrorAction SilentlyContinue) { break }
    Start-Sleep -Milliseconds 250
}
if (-not (Get-NetTCPConnection -State Listen -LocalPort $Port -ErrorAction SilentlyContinue)) { throw 'MySQL未就绪，查看.data/mysql.stderr.log。' }
if (Test-Path -LiteralPath $taskEnvironment) { Write-Host ('MySQL已恢复运行：127.0.0.1:' + $Port); exit 0 }

# 新的本地合成数据库使用随机口令；只写ignored文件，不进入命令参数、日志或仓库。
$taskRandomBytes = New-Object byte[] 24
$taskRandom = [System.Security.Cryptography.RandomNumberGenerator]::Create()
try { $taskRandom.GetBytes($taskRandomBytes) } finally { $taskRandom.Dispose() }
$taskPassword = [System.Convert]::ToBase64String($taskRandomBytes).Replace('+','a').Replace('/','b').Replace('=','c')
$taskSql = "CREATE DATABASE study_room_reserve CHARACTER SET utf8mb4; CREATE DATABASE study_room_test CHARACTER SET utf8mb4; CREATE USER 'study_room'@'localhost' IDENTIFIED BY '$taskPassword'; CREATE USER 'study_room'@'127.0.0.1' IDENTIFIED BY '$taskPassword'; GRANT ALL ON study_room_reserve.* TO 'study_room'@'localhost'; GRANT ALL ON study_room_test.* TO 'study_room'@'localhost'; GRANT ALL ON study_room_reserve.* TO 'study_room'@'127.0.0.1'; GRANT ALL ON study_room_test.* TO 'study_room'@'127.0.0.1'; ALTER USER 'root'@'localhost' IDENTIFIED BY '$taskPassword';"
$taskSql | & (Join-Path $taskMysqlBase 'bin\mysql.exe') --host=127.0.0.1 ('--port=' + $Port) --user=root
if ($LASTEXITCODE -ne 0) { throw '首次私有账号创建失败；保留数据，不自动删除或重建。' }
$taskEnv = "`$env:DB_URL='jdbc:mysql://127.0.0.1:$Port/study_room_reserve?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai'`n`$env:DB_USERNAME='study_room'`n`$env:DB_PASSWORD='$taskPassword'`n`$env:DEMO_SEED='true'`n"
[System.IO.File]::WriteAllText($taskEnvironment, $taskEnv, [System.Text.UTF8Encoding]::new($false))
[System.IO.File]::WriteAllText((Join-Path $taskDataRoot 'mysql-client.cnf'), "[client]`nhost=127.0.0.1`nport=$Port`nuser=study_room`npassword=$taskPassword`n", [System.Text.UTF8Encoding]::new($false))
Write-Host ('本地MySQL配置完成：127.0.0.1:' + $Port + '；随机数据库口令只在.data中保存。')
