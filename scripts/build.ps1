param([switch]$SkipTests)
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'tooling.ps1')
Set-Location $Script:ProjectRoot
$taskEvidence = Join-Path $Script:ProjectRoot 'artifacts\build'
New-Item -ItemType Directory -Path $taskEvidence -Force | Out-Null

try {
    # 一条入口串行执行依赖恢复、前端检查与构建、后端编译及单测；失败不能伪装成成功。
    Push-Location (Join-Path $Script:ProjectRoot 'frontend')
    try {
        if (Test-Path -LiteralPath 'package-lock.json') { Invoke-ProjectNpm -Arguments @('ci','--no-fund','--no-audit') }
        else { Invoke-ProjectNpm -Arguments @('install','--no-fund','--no-audit') }
        if ($LASTEXITCODE -ne 0) { throw '前端依赖恢复失败。' }
        Invoke-ProjectNpm -Arguments @('run','check')
        if ($LASTEXITCODE -ne 0) { throw '前端语法检查失败。' }
        Invoke-ProjectNpm -Arguments @('run','build')
        if ($LASTEXITCODE -ne 0) { throw '前端构建失败。' }
        # 课程要求审计产出报告，但审计中的漏洞退出码不阻断编译/测试。
        $taskAuditLines = Invoke-ProjectNpm -Arguments @('audit','--json')
        $taskAuditExit = $LASTEXITCODE
        [System.IO.File]::WriteAllLines((Join-Path $taskEvidence 'npm-audit.json'), [string[]]$taskAuditLines, [System.Text.UTF8Encoding]::new($false))
        Write-Host ('依赖审计退出码：' + $taskAuditExit + '（报告已保存，按课程口径不阻断构建）')
    } finally { Pop-Location }

    $taskMaven = Get-ProjectMaven
    $taskMavenArgs = @('-B','-ntp','-f','backend/pom.xml','package')
    if ($SkipTests) { $taskMavenArgs += '-DskipTests'; Write-Host '明确跳过JUnit：此构建不能当作测试通过。' }
    & $taskMaven @taskMavenArgs
    if ($LASTEXITCODE -ne 0) { throw '后端编译或测试失败。' }

    # 静态资源复制到单独发布目录，避免改写Java源码或Flyway迁移。
    $taskRelease = Join-Path $Script:ProjectRoot 'artifacts\release'
    New-Item -ItemType Directory -Path $taskRelease -Force | Out-Null
    Copy-Item -LiteralPath (Join-Path $Script:ProjectRoot 'frontend\dist') -Destination $taskRelease -Recurse -Force
    Copy-Item -LiteralPath (Join-Path $Script:ProjectRoot 'backend\target\study-room-0.3.0.jar') -Destination $taskRelease -Force
    Write-Host '构建成功；单元测试实际计数见backend/target/surefire-reports，审计见artifacts/build/npm-audit.json。'
    exit 0
} catch {
    Write-Error $_
    exit 1
}
