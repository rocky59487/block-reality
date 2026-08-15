@echo off
setlocal
rem One click: put the engine where the game will find it, then launch the client.
rem
rem Needs a JDK 17 on PATH (or JAVA_HOME). Gradle itself comes from the wrapper, so
rem nothing else has to be installed.
rem
rem The engine binary is taken from dist\ if it is there. To build it yourself you
rem need CMake and a C++ compiler; see QUICKSTART.md.

cd /d "%~dp0"

if not exist "forge\run\" mkdir "forge\run"

if exist "dist\br-sidecar.exe" (
    copy /y "dist\br-sidecar.exe" "forge\run\" >nul
    echo engine: forge\run\br-sidecar.exe
) else if exist "sidecar\build-win\br-sidecar.exe" (
    copy /y "sidecar\build-win\br-sidecar.exe" "forge\run\" >nul
    echo engine: forge\run\br-sidecar.exe ^(from sidecar\build-win^)
) else (
    echo.
    echo   No br-sidecar.exe found in dist\ or sidecar\build-win\.
    echo   The game will still start; structural analysis will be off and will say so.
    echo   Type /br status in game to see every path it looked in.
    echo.
)

cd forge
call gradlew.bat runClient
endlocal
