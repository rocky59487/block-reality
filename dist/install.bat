@echo off
setlocal enabledelayedexpansion
rem Copies the mod and the engine into a Minecraft instance.
rem
rem   install.bat "%APPDATA%\.minecraft"
rem   install.bat "C:\Users\you\AppData\Roaming\PrismLauncher\instances\BR-Dev\.minecraft"

set "HERE=%~dp0"
set "TARGET=%~1"

if "%TARGET%"=="" (
    echo usage: install.bat ^<minecraft instance directory^>
    echo   the directory that contains mods\, config\ and saves\
    exit /b 2
)
if not exist "%TARGET%\" (
    echo not a directory: %TARGET%
    exit /b 2
)

if not exist "%TARGET%\mods\" mkdir "%TARGET%\mods"

set "JAR="
for %%f in ("%HERE%blockreality-*.jar") do set "JAR=%%f"
if "!JAR!"=="" (
    echo no blockreality-*.jar next to this script
    exit /b 1
)

rem Two copies of the same mod in mods\ is a load error, so the old one goes first.
del /q "%TARGET%\mods\blockreality-*.jar" 2>nul
copy /y "!JAR!" "%TARGET%\mods\" >nul
echo mod    -^> %TARGET%\mods\
for %%f in ("!JAR!") do echo          %%~nxf

rem The engine goes in the game directory, which is where SidecarLocator looks.
if exist "%HERE%br-sidecar.exe" (
    copy /y "%HERE%br-sidecar.exe" "%TARGET%\" >nul
    echo engine -^> %TARGET%\br-sidecar.exe
)

echo.
echo Done. Launch Minecraft 1.20.1 with Forge 47.x.
echo In game, /br status will say whether the engine was found.
endlocal
