@echo off
rem Cho phep Java va lenh ping qua Windows Firewall (hoi quyen Admin). Chay tren CA 2 may.
chcp 65001 >nul
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0scripts\setup.ps1" -FirewallOnly
echo.
pause
