@echo off
chcp 65001 >nul
rem 仅停止本项目保存的前后端PID，不停止其他Java/Node或数据库程序。
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0scripts\stop.ps1"
exit /b %errorlevel%
