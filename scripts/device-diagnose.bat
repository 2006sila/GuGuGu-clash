@echo off
chcp 65001 >nul
echo 正在收集手机诊断信息，请确保手机已解锁并已允许 USB 调试...
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0device-diagnose.ps1"
echo.
echo 完成后请把 device-report.txt 的内容发给我。
pause
