@echo off
rem 仅停止本项目保存的前后端PID，不停止其他Java/Node或数据库程序。
chcp 65001 >nul
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0scripts\stop.ps1"
exit /b %errorlevel%
