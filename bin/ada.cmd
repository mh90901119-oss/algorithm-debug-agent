@echo off
setlocal EnableExtensions DisableDelayedExpansion

for %%I in ("%~dp0..") do set "ADA_REPOSITORY_ROOT=%%~fI"

if exist "%~dp0ada.local.cmd" call "%~dp0ada.local.cmd"

if not defined ADA_CLI_JAR (
    for %%F in ("%ADA_REPOSITORY_ROOT%\algorithm-debug-cli\target\algorithm-debug-cli-*-all.jar") do set "ADA_CLI_JAR=%%~fF"
)
if not exist "%ADA_CLI_JAR%" (
    1>&2 echo ADA CLI JAR not found. Build it with: mvn -Pcodepath-launcher package
    exit /b 10
)

if not defined ADA_CODEPATH_LAUNCHER_JAR (
    for %%F in ("%ADA_REPOSITORY_ROOT%\tools\code-path-tracer-junit-launcher\target\code-path-tracer-junit-launcher-*.jar") do set "ADA_CODEPATH_LAUNCHER_JAR=%%~fF"
)
if not exist "%ADA_CODEPATH_LAUNCHER_JAR%" (
    1>&2 echo CodePath Launcher JAR not found. Build it with: mvn -Pcodepath-launcher package
    exit /b 10
)

if not defined ADA_CODEPATH_LAUNCHER_SHA256 (
    for /f "usebackq delims=" %%H in (`powershell.exe -NoProfile -NonInteractive -Command "(Get-FileHash -LiteralPath $env:ADA_CODEPATH_LAUNCHER_JAR -Algorithm SHA256).Hash.ToLowerInvariant()"`) do set "ADA_CODEPATH_LAUNCHER_SHA256=%%H"
)
if not defined ADA_CODEPATH_LAUNCHER_SHA256 (
    1>&2 echo Unable to calculate CodePath Launcher SHA-256.
    exit /b 10
)

set "ADA_JAVA=java.exe"
if defined JAVA_HOME if exist "%JAVA_HOME%\bin\java.exe" set "ADA_JAVA=%JAVA_HOME%\bin\java.exe"

"%ADA_JAVA%" -jar "%ADA_CLI_JAR%" %*
set "ADA_EXIT_CODE=%ERRORLEVEL%"
endlocal & exit /b %ADA_EXIT_CODE%
