@echo off
setlocal
title Happy4J Update
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0Apply-Update.ps1"
set "CODE=%ERRORLEVEL%"
echo.
if not "%CODE%"=="0" (
  echo Update was not applied. Existing installation should remain unchanged.
) else (
  echo Please restart Happy4J with your original launcher.
)
echo.
pause
exit /b %CODE%
