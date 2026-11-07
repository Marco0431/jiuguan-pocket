@echo off
chcp 65001 >nul
setlocal
where node >nul 2>nul
if errorlevel 1 (
  echo 需要先安装 Node.js 24.5 或更新版本。
  pause
  exit /b 1
)
node "%~dp0start-phone-relay.mjs" %*
if errorlevel 1 pause
endlocal
