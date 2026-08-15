@echo off
setlocal enabledelayedexpansion
rem Block Reality installer.
rem
rem Double-click it, or pass an instance:
rem   install.bat "%APPDATA%\.minecraft"
rem
rem With no argument it looks for Minecraft instances in the usual places. It only
rem installs automatically when it finds EXACTLY ONE — guessing between several and
rem silently picking the wrong one is worse than asking.

set "HERE=%~dp0"
set "TARGET=%~1"

echo.
echo   Block Reality - Demo v0
echo   =======================
echo.

if not "%TARGET%"=="" goto :have_target

rem ---------------------------------------------------------------- autodetect
set "COUNT=0"
set "FOUND="

call :try "%APPDATA%\.minecraft"
for /d %%i in ("%APPDATA%\PrismLauncher\instances\*") do call :try "%%~i\.minecraft"
for /d %%i in ("%APPDATA%\MultiMC\instances\*")       do call :try "%%~i\.minecraft"
for /d %%i in ("%APPDATA%\ModrinthApp\profiles\*")    do call :try "%%~i"
for /d %%i in ("%USERPROFILE%\curseforge\minecraft\Instances\*") do call :try "%%~i"

if "%COUNT%"=="0" (
    echo   No Minecraft instance found.
    echo.
    echo   Looked in:
    echo     %%APPDATA%%\.minecraft
    echo     %%APPDATA%%\PrismLauncher\instances\*
    echo     %%APPDATA%%\MultiMC\instances\*
    echo     %%APPDATA%%\ModrinthApp\profiles\*
    echo     %%USERPROFILE%%\curseforge\minecraft\Instances\*
    echo.
    echo   Run it with the folder instead - the one containing mods\ and saves\:
    echo     install.bat "D:\games\my-instance\.minecraft"
    goto :done
)
if not "%COUNT%"=="1" (
    echo   Found %COUNT% instances. Pick one and pass it in:
    echo.
    for /f "tokens=1,* delims=|" %%a in ('type "%TEMP%\br_instances.txt"') do echo     install.bat "%%b"
    goto :done
)
set "TARGET=!FOUND!"
echo   Found one instance:
echo     !TARGET!
echo.

:have_target
if not exist "%TARGET%\" (
    echo   Not a directory: %TARGET%
    goto :done
)

if not exist "%TARGET%\mods\" mkdir "%TARGET%\mods"

set "JAR="
for %%f in ("%HERE%blockreality-*.jar") do set "JAR=%%f"
if "!JAR!"=="" (
    echo   No blockreality-*.jar next to this script.
    goto :done
)

rem Two copies of the same mod in mods\ is a load error, so the old one goes first.
del /q "%TARGET%\mods\blockreality-*.jar" 2>nul
copy /y "!JAR!" "%TARGET%\mods\" >nul
for %%f in ("!JAR!") do echo   mod    -^> %TARGET%\mods\%%~nxf

rem The engine goes in the game directory, which is where the mod looks for it.
if exist "%HERE%br-sidecar.exe" (
    copy /y "%HERE%br-sidecar.exe" "%TARGET%\" >nul
    echo   engine -^> %TARGET%\br-sidecar.exe
) else (
    echo   WARNING: no br-sidecar.exe next to this script.
    echo            The mod will load and play, but analysis will be off.
)

echo.
echo   Done. You still need Minecraft 1.20.1 with Forge 47.x in that instance.
echo   In game: creative tab "Block Reality", and /br status if anything looks wrong.
goto :done

rem ------------------------------------------------------------------ helpers
:try
if exist "%~1\mods\" (
    set /a COUNT+=1
    set "FOUND=%~1"
    if "!COUNT!"=="1" (echo %COUNT%^|%~1> "%TEMP%\br_instances.txt") else (echo !COUNT!^|%~1>> "%TEMP%\br_instances.txt")
)
exit /b

:done
echo.
rem Double-clicked windows close instantly without this.
pause
endlocal
