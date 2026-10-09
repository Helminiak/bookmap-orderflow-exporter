@echo off
setlocal
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0Smoke-Test.ps1" %*
set "SMOKE_RESULT=%ERRORLEVEL%"
echo.
pause
exit /b %SMOKE_RESULT%
