@echo off
rem Kiem tra duong mang toi may kia:  kiemtra.bat 192.168.1.20
chcp 65001 >nul
set "PEER=%~1"
if "%PEER%"=="" set /p "PEER=Nhap IP may kia (xem bang ipconfig tren may do): "
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0scripts\setup.ps1" -CheckPeer "%PEER%"
echo.
pause
