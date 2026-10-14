param([switch]$WithMySql, [switch]$WithNode)
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'tooling.ps1')
$taskTools = Join-Path $Script:ProjectRoot '.tools'
New-Item -ItemType Directory -Path $taskTools -Force | Out-Null

function Save-OfficialDownload {
    param([string]$Url,[string]$Destination)
    # 只从官方固定URL下载；临时工具全部被.gitignore排除，不改用户全局安装。
    if (-not (Test-Path -LiteralPath $Destination)) {
        Write-Host ('下载：' + $Url)
        $taskHttp = New-Object System.Net.WebClient
        try { $taskHttp.DownloadFile($Url, $Destination) } finally { $taskHttp.Dispose() }
    }
}

# Maven固定版本，使用官方Maven Central SHA512验证压缩包。
$taskMavenHome = Join-Path $taskTools 'apache-maven-3.9.9'
if (-not (Test-Path -LiteralPath (Join-Path $taskMavenHome 'bin\mvn.cmd'))) {
    $taskMavenUrl = 'https://repo.maven.apache.org/maven2/org/apache/maven/apache-maven/3.9.9/apache-maven-3.9.9-bin.zip'
    $taskArchive = Join-Path $taskTools 'apache-maven-3.9.9-bin.zip'
    Save-OfficialDownload $taskMavenUrl $taskArchive
    $taskExpected = ((Invoke-RestMethod ($taskMavenUrl + '.sha512')).ToString().Trim() -split '\s+')[0]
    if ((Get-FileHash -LiteralPath $taskArchive -Algorithm SHA512).Hash.ToLowerInvariant() -ne $taskExpected.ToLowerInvariant()) { throw 'Maven SHA512校验失败。' }
    Expand-Archive -LiteralPath $taskArchive -DestinationPath $taskTools -Force
}

# Node可选择单独准备；已安装Node或Codex运行时的电脑无需此下载。
if ($WithNode) {
    $taskNodeName = 'node-v24.19.0-win-x64.zip'
    $taskNodeArchive = Join-Path $taskTools $taskNodeName
    $taskNodeHome = Join-Path $taskTools 'node-v24.19.0-win-x64'
    if (-not (Test-Path -LiteralPath (Join-Path $taskNodeHome 'node.exe'))) {
        Save-OfficialDownload ('https://nodejs.org/dist/v24.19.0/' + $taskNodeName) $taskNodeArchive
        $taskNodeSums = Invoke-RestMethod 'https://nodejs.org/dist/v24.19.0/SHASUMS256.txt'
        $taskNodeLine = @($taskNodeSums -split "`n" | Where-Object { $_.Trim().EndsWith($taskNodeName) })
        if ($taskNodeLine.Count -ne 1) { throw 'Node官方校验记录不唯一。' }
        $taskNodeExpected = ($taskNodeLine[0] -split '\s+')[0]
        if ((Get-FileHash -LiteralPath $taskNodeArchive -Algorithm SHA256).Hash.ToLowerInvariant() -ne $taskNodeExpected.ToLowerInvariant()) { throw 'Node SHA256校验失败。' }
        Expand-Archive -LiteralPath $taskNodeArchive -DestinationPath $taskTools -Force
    }
}

# 通过npm官方注册表及integrity摘要准备npm，不下载第三方安装器。
$taskNpmCli = Join-Path $taskTools 'npm\package\bin\npm-cli.js'
if (-not (Test-Path -LiteralPath $taskNpmCli)) {
    $taskNpmMeta = Invoke-RestMethod 'https://registry.npmjs.org/npm/11.6.2'
    $taskNpmArchive = Join-Path $taskTools 'npm-11.6.2.tgz'
    Save-OfficialDownload $taskNpmMeta.dist.tarball $taskNpmArchive
    $taskNpmHash = [System.Convert]::ToBase64String([System.Security.Cryptography.SHA512]::Create().ComputeHash([System.IO.File]::ReadAllBytes($taskNpmArchive)))
    if ('sha512-' + $taskNpmHash -ne $taskNpmMeta.dist.integrity) { throw 'npm integrity校验失败。' }
    $taskNpmHome = Join-Path $taskTools 'npm'
    New-Item -ItemType Directory -Path $taskNpmHome -Force | Out-Null
    & tar -xzf $taskNpmArchive -C $taskNpmHome
    if ($LASTEXITCODE -ne 0) { throw 'npm解压失败。' }
}

if ($WithMySql) {
    $taskMysqlHome = Join-Path $taskTools 'mysql-8.0.44-winx64'
    if (-not (Test-Path -LiteralPath (Join-Path $taskMysqlHome 'bin\mysqld.exe'))) {
        $taskMysqlArchive = Join-Path $taskTools 'mysql-8.0.44-winx64.zip'
        Save-OfficialDownload 'https://cdn.mysql.com/Downloads/MySQL-8.0/mysql-8.0.44-winx64.zip' $taskMysqlArchive
        Expand-Archive -LiteralPath $taskMysqlArchive -DestinationPath $taskTools -Force
        Write-Host ('MySQL官方压缩包 SHA256：' + (Get-FileHash -LiteralPath $taskMysqlArchive -Algorithm SHA256).Hash)
    }
}
Write-Host '工具准备完成。仍需JDK21；工具和本地数据库凭据不会上传GitHub。'
