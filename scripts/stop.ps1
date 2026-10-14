$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'tooling.ps1')
# 只停止PID文件指向且命令行归属本项目的前后端，保留MySQL与持久数据。
foreach ($taskService in @('backend','frontend')) {
    $taskPidFile = Join-Path $Script:ProjectRoot ('.data\' + $taskService + '.pid')
    if (-not (Test-Path -LiteralPath $taskPidFile)) { continue }
    $taskProcessId = [int](Get-Content -LiteralPath $taskPidFile)
    $taskProcess = Get-CimInstance Win32_Process -Filter ('ProcessId=' + $taskProcessId)
    if (-not $taskProcess) { continue }
    $taskExpected = if ($taskService -eq 'backend') { 'study-room-0.3.0.jar' } else { 'vite' }
    if (-not $taskProcess.CommandLine -or -not $taskProcess.CommandLine.Contains($taskExpected)) { throw 'PID已被其他程序复用，停止操作已拒绝。' }
    # Windows的Java或MySQL式启动器可能派生真正服务进程；只处理同一已保存父PID的匹配子进程。
    $taskChildren = @(Get-CimInstance Win32_Process -Filter ('ParentProcessId=' + $taskProcessId))
    foreach ($taskChild in $taskChildren) {
        if ($taskChild.CommandLine -and $taskChild.CommandLine.Contains($taskExpected)) {
            Stop-Process -Id $taskChild.ProcessId -ErrorAction SilentlyContinue
        }
    }
    Stop-Process -Id $taskProcessId -ErrorAction SilentlyContinue
    Write-Host ('已停止本项目' + $taskService)
}
Write-Host 'MySQL与.data中的持久数据保留，便于下次继续使用。'
