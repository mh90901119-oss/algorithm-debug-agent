@echo off
setlocal EnableExtensions DisableDelayedExpansion

if /I not "%~1"=="--project" goto :invalid_arguments
if "%~2"=="" goto :invalid_arguments
if not "%~3"=="" goto :invalid_arguments

for %%I in ("%~dp0..") do set "ADA_REPOSITORY_ROOT=%%~fI"
powershell.exe -NoLogo -NoProfile -NonInteractive -ExecutionPolicy Bypass ^
  -File "%ADA_REPOSITORY_ROOT%\scripts\run-mcp.ps1" -ProjectRoot "%~2"
set "ADA_EXIT_CODE=%ERRORLEVEL%"
endlocal & exit /b %ADA_EXIT_CODE%

:invalid_arguments
>&2 echo ADA_MCP_INVALID_ARGUMENTS: expected --project ^<absolute-project-directory^>
endlocal & exit /b 2
