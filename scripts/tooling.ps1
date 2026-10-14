# 共享工具解析仅查找明确的项目工具、系统命令和Codex已知运行时，不打印环境凭据。
$Script:ProjectRoot = Split-Path -Parent $PSScriptRoot

function Get-ProjectNode {
    # 优先系统Node，其次Codex已配置的运行时，最后使用项目独立下载的版本。
    $taskNodeCommand = Get-Command node -ErrorAction SilentlyContinue
    if ($taskNodeCommand) { return $taskNodeCommand.Source }
    $taskCandidates = @(
        (Join-Path $env:USERPROFILE '.cache\codex-runtimes\codex-primary-runtime\dependencies\node\bin\node.exe'),
        (Join-Path $Script:ProjectRoot '.tools\node-v24.19.0-win-x64\node.exe')
    )
    foreach ($taskCandidate in $taskCandidates) { if (Test-Path -LiteralPath $taskCandidate) { return $taskCandidate } }
    throw '未找到Node.js。请安装Node.js 22或24，或执行scripts/bootstrap.ps1 -WithNode。'
}

function Invoke-ProjectNpm {
    param([Parameter(Mandatory=$true)][string[]]$Arguments)
    # 不依赖全局npm脚本策略；优先使用与项目一起准备的npm CLI。
    $taskNode = Get-ProjectNode
    $env:PATH = (Split-Path -Parent $taskNode) + ';' + $env:PATH
    $taskNpmCli = Join-Path $Script:ProjectRoot '.tools\npm\package\bin\npm-cli.js'
    if (Test-Path -LiteralPath $taskNpmCli) { & $taskNode $taskNpmCli @Arguments }
    else {
        $taskNpm = Get-Command npm.cmd -ErrorAction SilentlyContinue
        if (-not $taskNpm) { throw '未找到npm，请先执行scripts/bootstrap.ps1。' }
        & $taskNpm.Source @Arguments
    }
    # 调用者可读取退出码；构建脚本只对编译/测试失败退出，依赖审计另留报告。
}

function Get-ProjectMaven {
    $taskPortable = Join-Path $Script:ProjectRoot '.tools\apache-maven-3.9.9\bin\mvn.cmd'
    if (Test-Path -LiteralPath $taskPortable) { return $taskPortable }
    $taskMaven = Get-Command mvn.cmd -ErrorAction SilentlyContinue
    if ($taskMaven) { return $taskMaven.Source }
    throw '未找到Maven，请先执行scripts/bootstrap.ps1。'
}

function Get-AsciiWorkspace {
    # MySQL Windows原生程序不可靠地处理中文安装路径；ASCII联接仍指向同一个项目。
    $taskDigest = [System.Security.Cryptography.SHA256]::Create()
    try { $taskHashBytes = $taskDigest.ComputeHash([System.Text.Encoding]::UTF8.GetBytes($Script:ProjectRoot)) }
    finally { $taskDigest.Dispose() }
    $taskSuffix = ([System.BitConverter]::ToString($taskHashBytes)).Replace('-','').Substring(0,8).ToLowerInvariant()
    $taskAlias = Join-Path $env:USERPROFILE ('.cache\study-room-' + $taskSuffix)
    if (Test-Path -LiteralPath $taskAlias) {
        $taskItem = Get-Item -LiteralPath $taskAlias
        if ($taskItem.LinkType -ne 'Junction' -or $taskItem.Target -ne $Script:ProjectRoot) { throw '运行时联接已被其他目录占用，停止以免覆盖。' }
    } else {
        New-Item -ItemType Directory -Path (Split-Path -Parent $taskAlias) -Force | Out-Null
        New-Item -ItemType Junction -Path $taskAlias -Value $Script:ProjectRoot | Out-Null
    }
    return $taskAlias
}
