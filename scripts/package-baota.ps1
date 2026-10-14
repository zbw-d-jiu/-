# 宝塔生产发布包：输入指定输出目录，生成dist/可执行JAR/配置/说明/校验和/ZIP。
# 默认执行npm build与mvn clean package -DskipTests；跳测试不冒充测试通过。
# -SkipBuild仅用于已完成独立验证的同一批产物；目标非空立即停止，绝不删除旧包或用户文件。
param(
    [string]$OutputDirectory = (Join-Path $env:USERPROFILE 'Desktop\校园自习室预约系统打包'),
    [switch]$SkipBuild
)
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'tooling.ps1')
Set-Location $Script:ProjectRoot
$pkgOutput = [IO.Path]::GetFullPath($OutputDirectory)
if (Test-Path -LiteralPath $pkgOutput) {
    if (Get-ChildItem -LiteralPath $pkgOutput -Force | Select-Object -First 1) { throw '输出目录非空，保留原文件。请选择新的空目录。' }
}
if (-not $SkipBuild) {
    # 停止本项目保存PID对应服务，避免Windows esbuild占用；不停止MySQL或其他项目。
    & (Join-Path $PSScriptRoot 'stop.ps1')
    Push-Location (Join-Path $Script:ProjectRoot 'frontend')
    try {
        Invoke-ProjectNpm @('ci'); if ($LASTEXITCODE -ne 0) { throw 'npm ci失败。' }
        Invoke-ProjectNpm @('run','check'); if ($LASTEXITCODE -ne 0) { throw '前端检查失败。' }
        Invoke-ProjectNpm @('run','build'); if ($LASTEXITCODE -ne 0) { throw '生产前端构建失败。' }
    } finally { Pop-Location }
    & (Get-ProjectMaven) -B -ntp -f backend/pom.xml clean package -DskipTests
    if ($LASTEXITCODE -ne 0) { throw '生产后端构建失败。' }
}
$pkgJar = Join-Path $Script:ProjectRoot 'backend\target\study-room-0.3.0.jar'
$pkgDist = Join-Path $Script:ProjectRoot 'frontend\dist'
if (-not (Test-Path -LiteralPath $pkgJar) -or -not (Test-Path -LiteralPath (Join-Path $pkgDist 'index.html'))) { throw '缺少构建产物。' }

# 先检查普通生产jar，防止把可选-Pdemo包误发。检查不解压、不执行、不打印凭据。
Add-Type -AssemblyName System.IO.Compression.FileSystem
$pkgZip = [IO.Compression.ZipFile]::OpenRead($pkgJar)
try {
    $pkgEntries = @($pkgZip.Entries | ForEach-Object { $_.FullName })
    if ($pkgEntries -match 'BOOT-INF/lib/h2-' -or $pkgEntries -contains 'BOOT-INF/classes/application-demo.yml' -or $pkgEntries -contains 'BOOT-INF/classes/application-test.yml') { throw '发现H2或开发配置，拒绝作为生产包。' }
    if ($pkgEntries -notcontains 'BOOT-INF/classes/application-prod.yml') { throw 'JAR缺少prod配置。' }
} finally { $pkgZip.Dispose() }
New-Item -ItemType Directory -Path $pkgOutput -Force | Out-Null
$pkgBackend = Join-Path $pkgOutput 'backend'
$pkgNginx = Join-Path $pkgOutput 'nginx'
New-Item -ItemType Directory -Path $pkgBackend,$pkgNginx -Force | Out-Null
Copy-Item -LiteralPath $pkgDist -Destination $pkgOutput -Recurse
Copy-Item -LiteralPath $pkgJar -Destination $pkgBackend
Copy-Item -LiteralPath 'backend/src/main/resources/application-prod.yml' -Destination $pkgBackend
Copy-Item -LiteralPath 'deploy/baota/production.env.example' -Destination $pkgBackend
Copy-Item -LiteralPath 'deploy/baota/nginx-locations.conf','deploy/baota/nginx-https-server.conf' -Destination $pkgNginx
Copy-Item -LiteralPath 'deploy/baota/README-宝塔部署.md' -Destination $pkgOutput
# 验证记录有则随包带入；新电脑单纯重建时没有验证报告不能虚构本机通过。
foreach ($pkgReport in @('docs/testing/生产打包独立测试记录.md','deploy/baota/构建与代理验证记录.md')) {
    if (Test-Path -LiteralPath $pkgReport) { Copy-Item -LiteralPath $pkgReport -Destination $pkgOutput }
}
# 校验和仅使用包内相对路径；不包含本机来源目录、口令和token，不对自身计算hash。
$pkgHashes = @(Get-ChildItem -LiteralPath $pkgOutput -File -Recurse | Sort-Object FullName | ForEach-Object {
    $pkgRelative = $_.FullName.Substring($pkgOutput.Length + 1).Replace('\','/')
    (Get-FileHash -LiteralPath $_.FullName -Algorithm SHA256).Hash.ToLowerInvariant() + '  ' + $pkgRelative
})
[IO.File]::WriteAllLines((Join-Path $pkgOutput 'SHA256SUMS.txt'),[string[]]$pkgHashes,[Text.UTF8Encoding]::new($false))
Compress-Archive -Path (Join-Path $pkgOutput '*') -DestinationPath (Join-Path $pkgOutput '校园自习室预约系统-生产发布.zip') -CompressionLevel Optimal
Write-Host ('生产包已生成：' + $pkgOutput)
Write-Host '上传dist内部文件到站点根目录；JAR放独立Java项目目录，指定JDK21及prod。'
