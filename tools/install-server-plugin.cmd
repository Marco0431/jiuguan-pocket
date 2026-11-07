@echo off
chcp 65001 >nul
setlocal
where node >nul 2>nul
if errorlevel 1 (
  echo 需要先安装支持本插件的新版 Node.js。
  pause
  exit /b 1
)
node "%~dp0install-server-plugin.mjs" %*
pause
endlocal
