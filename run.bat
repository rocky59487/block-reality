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
rem Native library setup: docs\GAME_RUNTIME.md. dist\ is the historical v0.3c release.

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

echo   Native engine: set BR_ENGINE to a compatible DLL, or configure enginePath.
echo   The development jar contains no engine by default. /br status reports its source.
echo   See docs\GAME_RUNTIME.md for placement and migration instructions.

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
