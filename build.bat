@echo off
rem Windows一键构建入口；具体检查及错误退出码由PowerShell脚本统一处理。
chcp 65001 >nul
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0scripts\build.ps1" %*
exit /b %errorlevel%
