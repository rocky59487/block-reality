@echo off
rem Block Reality installer / 安裝器
rem
rem   Double-click it, or pass an instance:
rem     install.bat "%APPDATA%\.minecraft"
rem     install.bat --list
rem
rem Chinese text below only ever appears inside echo strings — every byte of it is
rem >= 0x80, so cmd cannot mistake it for an operator no matter what codepage is
rem active. Worst case it renders as mojibake; the script still runs.
rem
rem Delayed expansion stays OFF for the whole script: with it on, %-expansion eats
rem any "!" inside a user path (C:\Users\ming!\...) and the install lands in a
rem directory that does not exist. Array reads use `call` double-expansion instead.
chcp 65001 >nul 2>&1
setlocal disabledelayedexpansion

set "HERE=%~dp0"
set "TARGET=%~1"
set "LIST_ONLY="
if /i "%TARGET%"=="--list" (set "LIST_ONLY=1" & set "TARGET=")
if /i "%TARGET%"=="-l"     (set "LIST_ONLY=1" & set "TARGET=")

echo.
echo   Block Reality - 安裝器 / installer
echo   ==================================
echo.

if not "%TARGET%"=="" goto :have_target

rem ---------------------------------------------------------------- autodetect
set "COUNT=0"

call :try "%APPDATA%\.minecraft"
for /d %%i in ("%APPDATA%\PrismLauncher\instances\*")           do call :try "%%~i\.minecraft"
for /d %%i in ("%APPDATA%\MultiMC\instances\*")                 do call :try "%%~i\.minecraft"
for /d %%i in ("%APPDATA%\ModrinthApp\profiles\*")              do call :try "%%~i"
for /d %%i in ("%USERPROFILE%\curseforge\minecraft\Instances\*") do call :try "%%~i"
for /d %%i in ("%APPDATA%\.minecraft\..\.technic\modpacks\*")   do call :try "%%~i"

if "%COUNT%"=="0" (
    echo   找不到 Minecraft 實例。 / No Minecraft instance found.
    echo.
    echo   找過這些地方 / looked in:
    echo     %%APPDATA%%\.minecraft
    echo     %%APPDATA%%\PrismLauncher\instances\*
    echo     %%APPDATA%%\MultiMC\instances\*
    echo     %%APPDATA%%\ModrinthApp\profiles\*
    echo     %%USERPROFILE%%\curseforge\minecraft\Instances\*
    echo.
    echo   直接把遊戲目錄給它就好（裡面有 mods\ 和 saves\ 的那層）：
    echo   Pass the game directory instead - the one containing mods\ and saves\:
    echo     install.bat "D:\games\my-instance\.minecraft"
    goto :done
)

if defined LIST_ONLY (
    for /l %%n in (1,1,%COUNT%) do call :show %%n
    goto :done
)

if "%COUNT%"=="1" (
    set "TARGET=%INST_1%"
    echo   找到一個實例 / found one instance:
    echo     %INST_1%
    echo.
    goto :have_target
)

echo   找到 %COUNT% 個實例。要裝到哪一個？
echo   Found %COUNT% instances. Which one?
echo.
for /l %%n in (1,1,%COUNT%) do call :show %%n
echo.
set "PICK="
set /p "PICK=  輸入編號 / enter a number [1-%COUNT%]: "
if not defined PICK goto :badpick
rem A non-numeric answer makes INST_<answer> undefined, which is exactly the check below.
call set "TARGET=%%INST_%PICK%%%"
if not defined TARGET goto :badpick
echo.
goto :have_target

:badpick
echo.
echo   不是有效的編號 / not a valid choice.
goto :done

:have_target
if not exist "%TARGET%\" (
    echo   這不是一個資料夾 / not a directory: %TARGET%
    goto :done
)

rem -------------------------------------------------------------------- install
rem Every copy is CHECKED. Reporting "installed" after a copy silently failed
rem (read-only folder, disk full, antivirus hold) sends the user hunting in-game
rem for a mod that is not there.
if not exist "%TARGET%\mods\" mkdir "%TARGET%\mods"
if not exist "%TARGET%\mods\" (
    echo   無法建立 / cannot create: %TARGET%\mods
    goto :done
)

set "JAR="
for %%f in ("%HERE%blockreality-*.jar") do set "JAR=%%f"
if not defined JAR (
    echo   這個資料夾裡沒有 blockreality-*.jar。zip 有完整解壓縮嗎？
    echo   No blockreality-*.jar next to this script - did the zip fully extract?
    goto :done
)

rem Two copies of the same mod in mods\ is a load error, so the old one goes first.
del /q "%TARGET%\mods\blockreality-*.jar" 2>nul
copy /y "%JAR%" "%TARGET%\mods\" >nul
if errorlevel 1 (
    echo   複製 mod 失敗，沒有安裝任何東西。 / Copying the mod FAILED - nothing was installed.
    echo   來源 / from: %JAR%
    echo   目的 / to:   %TARGET%\mods\
    goto :done
)
for %%f in ("%JAR%") do echo   mod    -^> %TARGET%\mods\%%~nxf

rem The engine goes in the game directory, which is where the mod looks for it.
if exist "%HERE%br-sidecar.exe" (
    copy /y "%HERE%br-sidecar.exe" "%TARGET%\" >nul
    if errorlevel 1 (
        echo   複製引擎失敗。mod 已安裝、可遊玩，但分析會是關的。
        echo   Copying the engine FAILED. The mod IS installed and plays;
        echo   analysis stays off until br-sidecar.exe is copied into: %TARGET%
        goto :done
    )
    echo   engine -^> %TARGET%\br-sidecar.exe
) else (
    echo   警告：旁邊沒有 br-sidecar.exe。mod 照樣載入照樣玩，但分析是關的。
    echo   WARNING: no br-sidecar.exe next to this script - the mod loads and plays,
    echo            analysis is simply off.
)

echo.
call :forge "%TARGET%"
if defined HASFORGE (
    echo   [OK] 這個實例裡有 Forge。用你平常的啟動器開遊戲就好。
    echo        Forge found in this instance. Launch it the way you normally do.
) else (
    echo   [!!] 這個實例裡沒看到 Forge。Block Reality 需要 Minecraft 1.20.1 + Forge 47.x。
    echo        No Forge here. Block Reality needs Minecraft 1.20.1 + Forge 47.x:
    echo        https://files.minecraftforge.net/net/minecraftforge/index.html
)
echo.
echo   進遊戲後：創造分頁 "Block Reality"。有問題打 /br status。
echo   In game: creative tab "Block Reality". If anything looks off: /br status
goto :done

rem ------------------------------------------------------------------- helpers

rem :try <dir> — count it as an instance if it looks like a game directory.
rem mods\ alone is not enough: a fresh Forge instance that has never had a mod in
rem it has no mods\ yet, and that is precisely the person who needs this script.
rem Lines inside a called label are parsed per line, so %COUNT% below reads the
rem value the preceding set /a just wrote — no delayed expansion needed.
:try
set "CAND=%~1"
set "OK="
if exist "%CAND%\mods\" set "OK=1"
if exist "%CAND%\versions\" if exist "%CAND%\launcher_profiles.json" set "OK=1"
if exist "%CAND%\versions\" if exist "%CAND%\saves\" set "OK=1"
if not defined OK exit /b
set /a COUNT+=1
set "INST_%COUNT%=%CAND%"
exit /b

rem :forge <dir> — sets HASFORGE if Forge is installed in that instance.
rem CurseForge keeps neither versions\ nor libraries\ inside the instance — the
rem launcher manages the loader outside, and minecraftinstance.json is the only
rem thing in here that knows (#66). Match the "forgeVersion" key, not bare "forge":
rem the manifest's installPath always contains ...\curseforge\..., so bare "forge"
rem hits every instance and checks nothing; an instance with no loader has
rem baseModLoader null and no such key. This check exists to not flash a warning
rem at someone whose Forge works, not to verify version compatibility.
:forge
set "HASFORGE="
for /d %%v in ("%~1\versions\*forge*") do set "HASFORGE=1"
if exist "%~1\libraries\net\minecraftforge\" set "HASFORGE=1"
if exist "%~1\minecraftinstance.json" (
    findstr /i /c:"forgeVersion" "%~1\minecraftinstance.json" >nul 2>&1
    if not errorlevel 1 set "HASFORGE=1"
)
exit /b

rem :show <n> — print one numbered candidate, tagged with whether it has Forge.
:show
call set "SHOWP=%%INST_%~1%%"
call :forge "%SHOWP%"
if defined HASFORGE (
    echo     %~1^) [Forge]    %SHOWP%
) else (
    echo     %~1^) [no Forge] %SHOWP%
)
exit /b

:done
echo.
rem Double-clicked windows close instantly without this.
pause
endlocal
