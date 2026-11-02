@echo off
rem Mo app (sau khi da chay setup.bat). Mo 2 cua so:  run.bat -Instances 2
chcp 65001 >nul
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0scripts\setup.ps1" -Run -Quick %*
if errorlevel 1 pause
