@echo off
rem Chay Relay Server cho che do Internet tren may nay (can chay setup.bat truoc).
rem   relay.bat           relay trong mang noi bo: ws://<IP-LAN>:8080/ws
rem   relay.bat -Tunnel   cong khai qua Cloudflare Tunnel mien phi (2 may o 2 mang khac nhau, khong can VPS)
chcp 65001 >nul
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0scripts\relay.ps1" %*
if errorlevel 1 pause
