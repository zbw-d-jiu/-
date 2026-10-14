@echo off
chcp 65001 >nul
rem 启动本项目的本地前后端，默认真实MySQL；-Demo显式选择内存演示。
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0scripts\start.ps1" %*
exit /b %errorlevel%
