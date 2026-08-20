@echo off
setlocal
rem One click: launch Minecraft with Block Reality already in it.
rem
rem This runs the DEVELOPMENT client, which is the only one-click path that can exist:
rem Minecraft and Forge cannot be redistributed, so nothing shippable can contain them.
rem ForgeGradle downloads both itself on the first run (several minutes, once).
rem
rem Needs: a JDK 17 on PATH or in JAVA_HOME. Gradle comes from the wrapper.
rem
rem To use your normal launcher instead, run dist\install.bat and start it as usual.

cd /d "%~dp0"

echo.
echo   Block Reality - launching the development client
echo.

rem ------------------------------------------------------------------- java 17
set "JAVA_EXE=java"
if defined JAVA_HOME (
    set "JAVA_EXE=%JAVA_HOME%\bin\java.exe"
    set "PATH=%JAVA_HOME%\bin;%PATH%"
)

"%JAVA_EXE%" -version >nul 2>&1
if errorlevel 1 (
    echo   No Java found.
    echo.
    echo   Minecraft 1.20.1 needs a JDK 17. Install one, then either put it on PATH
    echo   or set JAVA_HOME to it. Temurin 17 is the usual choice:
    echo     https://adoptium.net/temurin/releases/?version=17
    goto :fail
)

rem ------------------------------------------------------------------- engine
if not exist "forge\run\" mkdir "forge\run"

rem A locally built engine beats the committed dist binary: after rebuilding the
rem sidecar, the run must exercise what was just built, not the last release.
if exist "sidecar\build-win\br-sidecar.exe" (
    copy /y "sidecar\build-win\br-sidecar.exe" "forge\run\" >nul
    echo   engine: forge\run\br-sidecar.exe ^(from sidecar\build-win^)
) else if exist "dist\br-sidecar.exe" (
    copy /y "dist\br-sidecar.exe" "forge\run\" >nul
    echo   engine: forge\run\br-sidecar.exe ^(from dist - no local build found^)
) else (
    echo   No br-sidecar.exe in dist\ or sidecar\build-win\.
    echo   The game will still start; analysis will be off and will say so.
    echo   Type /br status in game to see every path it looked in.
)

echo.
echo   Starting. The first run downloads Minecraft and Forge - give it a few minutes.
echo.

cd forge
call gradlew.bat runClient
if errorlevel 1 goto :fail
endlocal
exit /b 0

:fail
echo.
echo   Failed. If you were double-clicking, the messages above are the reason.
pause
endlocal
exit /b 1
