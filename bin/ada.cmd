@echo off
setlocal EnableExtensions DisableDelayedExpansion

for %%I in ("%~dp0..") do set "ADA_REPOSITORY_ROOT=%%~fI"
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%ADA_REPOSITORY_ROOT%\scripts\run-ada.ps1" %*
set "ADA_EXIT_CODE=%ERRORLEVEL%"
endlocal & exit /b %ADA_EXIT_CODE%
