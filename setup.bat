@echo off
rem Cai dat (JDK, Maven, thu vien, tuong lua) roi mo app. Chay 1 lan tren may moi.
rem Mo 2 cua so de thu tren 1 may:  setup.bat -Instances 2
chcp 65001 >nul
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0scripts\setup.ps1" -Run %*
echo.
pause
