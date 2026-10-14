# DEV-T12独立测试入口：只清理专用合成测试库，不启动或修改演示/正式数据库。
# 输入-MySql选择真实MySQL8；凭据仅从本地ignored文件加载，不输出。
# 输出JUnit执行日志和退出码，任何失败都保持非0；H2不能代替MySQL锁与迁移证据。
param([switch]$MySql, [string]$EvidenceName = '')
$ErrorActionPreference = 'Stop'
$qaRoot = (Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
$qaEvidence = Join-Path $qaRoot 'docs/testing/执行记录'
New-Item -ItemType Directory -Force -Path $qaEvidence | Out-Null
if ($MySql) {
    . (Join-Path $qaRoot '.data/mysql.env.ps1')
    $env:TEST_DB_URL = $env:DB_URL.Replace('/study_room_reserve?', '/study_room_test?')
    if ($env:TEST_DB_URL -notmatch '/study_room_test\?') { throw '只允许study_room_test独立库' }
    $env:TEST_DB_USERNAME = $env:DB_USERNAME
    $env:TEST_DB_PASSWORD = $env:DB_PASSWORD
    if (!$EvidenceName) { $EvidenceName = 'MySQL8-最终复测.log' }
} else {
    Remove-Item Env:TEST_DB_URL, Env:TEST_DB_USERNAME, Env:TEST_DB_PASSWORD -ErrorAction SilentlyContinue
    if (!$EvidenceName) { $EvidenceName = 'H2-最终复测.log' }
}
try {
    # Windows PowerShell将JVM警告视为NativeCommandError；保留stderr并以进程退出码判断测试结果。
    $ErrorActionPreference = 'Continue'
    & (Join-Path $qaRoot '.tools/apache-maven-3.9.9/bin/mvn.cmd') -f (Join-Path $qaRoot 'backend/pom.xml') test *> (Join-Path $qaEvidence $EvidenceName)
    $qaExitCode = $LASTEXITCODE
    $ErrorActionPreference = 'Stop'
    Get-Content -LiteralPath (Join-Path $qaEvidence $EvidenceName) -Tail 20
} finally {
    Remove-Item Env:TEST_DB_URL, Env:TEST_DB_USERNAME, Env:TEST_DB_PASSWORD -ErrorAction SilentlyContinue
}
exit $qaExitCode

