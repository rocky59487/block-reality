#!/usr/bin/env bash
# Block Reality installer / 安裝器
#
#   ./install.sh                    找一個 Minecraft 實例並安裝
#   ./install.sh ~/.minecraft       或直接告訴它裝到哪
#   ./install.sh --list             只列出找到的實例，不動任何東西
#
# 找到多個實例時會列出來讓你**選一個**。在沒有終端機可問的情況下（管線、CI）
# 它會列出來然後退出，而不是亂猜一個——猜錯比不裝更糟。
set -uo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
TARGET=""
LIST_ONLY=0

case "${1:-}" in
    --list|-l) LIST_ONLY=1 ;;
    --help|-h)
        sed -n '2,12p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'
        exit 0 ;;
    "") ;;
    *) TARGET="$1" ;;
esac

echo
echo "  Block Reality — 安裝器 / installer"
echo "  =================================="
echo

# ---------------------------------------------------------------- 找實例
# 一個資料夾算實例，條件是它長得像遊戲目錄：有 mods/、或有 versions/ 加
# launcher_profiles.json、或有 saves/。只認 mods/ 會漏掉「裝了 Forge 但還沒裝過
# 任何 mod」的全新實例，那正好是最需要這支腳本的人。
looks_like_instance() {
    [[ -d "$1/mods" ]] && return 0
    [[ -d "$1/versions" && -f "$1/launcher_profiles.json" ]] && return 0
    [[ -d "$1/saves" && -d "$1/versions" ]] && return 0
    return 1
}

# Forge 有沒有裝：找 versions/ 底下的 forge 資料夾，或 libraries 裡的 forge 座標。
has_forge() {
    compgen -G "$1/versions/*forge*" >/dev/null 2>&1 && return 0
    [[ -d "$1/libraries/net/minecraftforge" ]] && return 0
    return 1
}

CANDIDATES=()
if [[ -z "$TARGET" || "$LIST_ONLY" -eq 1 ]]; then
    add() { looks_like_instance "$1" && CANDIDATES+=("$1"); }

    add "$HOME/.minecraft"
    add "$HOME/Library/Application Support/minecraft"
    add "$HOME/.var/app/com.mojang.Minecraft/.minecraft"
    for d in "$HOME"/.local/share/PrismLauncher/instances/*/.minecraft \
             "$HOME"/.local/share/multimc/instances/*/.minecraft \
             "$HOME"/.local/share/ModrinthApp/profiles/* \
             "$HOME"/Library/Application\ Support/PrismLauncher/instances/*/.minecraft \
             "$HOME"/curseforge/minecraft/Instances/*; do
        [[ -d "$d" ]] && add "$d"
    done
fi

if [[ "$LIST_ONLY" -eq 1 ]]; then
    if [[ ${#CANDIDATES[@]} -eq 0 ]]; then
        echo "  找不到任何實例 / no instance found"
        exit 1
    fi
    for c in "${CANDIDATES[@]}"; do
        if has_forge "$c"; then echo "  [Forge]    $c"; else echo "  [no Forge] $c"; fi
    done
    exit 0
fi

if [[ -z "$TARGET" ]]; then
    if [[ ${#CANDIDATES[@]} -eq 0 ]]; then
        cat <<'MSG'
  找不到 Minecraft 實例。 / No Minecraft instance found.

  找過這些地方 / looked in:
    ~/.minecraft
    ~/Library/Application Support/minecraft
    ~/.local/share/PrismLauncher/instances/*/.minecraft
    ~/.local/share/multimc/instances/*/.minecraft
    ~/.local/share/ModrinthApp/profiles/*
    ~/curseforge/minecraft/Instances/*

  直接把遊戲目錄給它就好（就是裡面有 mods/ 和 saves/ 的那層）：
  Pass the game directory instead — the one containing mods/ and saves/:

    ./install.sh /path/to/instance/.minecraft
MSG
        exit 1
    fi

    if [[ ${#CANDIDATES[@]} -eq 1 ]]; then
        TARGET="${CANDIDATES[0]}"
        echo "  找到一個實例 / found one instance:"
        echo "    $TARGET"
        echo
    else
        echo "  找到 ${#CANDIDATES[@]} 個實例。要裝到哪一個？"
        echo "  Found ${#CANDIDATES[@]} instances. Which one?"
        echo
        i=1
        for c in "${CANDIDATES[@]}"; do
            if has_forge "$c"; then printf '    %d) [Forge]    %s\n' "$i" "$c"
            else                    printf '    %d) [no Forge] %s\n' "$i" "$c"; fi
            i=$((i + 1))
        done
        echo
        if [[ ! -t 0 ]]; then
            # 沒有終端機可以問，就不要猜。
            echo "  非互動模式，不猜。把要的那個當參數傳進來："
            echo "  Not interactive — pass the one you want as an argument:"
            echo "    ./install.sh \"${CANDIDATES[0]}\""
            exit 1
        fi
        printf '  輸入編號 / enter a number [1-%d]: ' "${#CANDIDATES[@]}"
        read -r PICK
        if ! [[ "$PICK" =~ ^[0-9]+$ ]] || (( PICK < 1 || PICK > ${#CANDIDATES[@]} )); then
            echo "  不是有效的編號 / not a valid choice: $PICK"
            exit 1
        fi
        TARGET="${CANDIDATES[$((PICK - 1))]}"
        echo
    fi
fi

if [[ ! -d "$TARGET" ]]; then
    echo "  這不是一個資料夾 / not a directory: $TARGET"
    exit 2
fi

# ---------------------------------------------------------------- 安裝
# 每一步複製都要驗過才往下走：報「裝好了」但其實半路失敗（唯讀目錄、磁碟滿），
# 比直接失敗更糟——使用者會去遊戲裡找一個不存在的 mod。
if ! mkdir -p "$TARGET/mods"; then
    echo "  無法建立 / cannot create: $TARGET/mods"
    exit 1
fi

JAR=$(ls "$HERE"/blockreality-*.jar 2>/dev/null | head -1 || true)
if [[ -z "$JAR" ]]; then
    echo "  這支腳本旁邊沒有 blockreality-*.jar。"
    echo "  No blockreality-*.jar next to this script — did the zip fully extract?"
    exit 1
fi

# mods/ 裡放兩份同一個 mod 會直接載入失敗，所以舊的先移掉。
rm -f "$TARGET"/mods/blockreality-*.jar
if ! cp "$JAR" "$TARGET/mods/"; then
    echo "  複製 mod 失敗 / copying the mod failed — nothing was installed."
    exit 1
fi
echo "  mod    -> $TARGET/mods/$(basename "$JAR")"

# 引擎放在遊戲目錄，那正是 mod 會去找的地方。
# The archive carries Windows and Linux x86-64 engines only. Copying the Linux
# ELF onto a Mac would report "installed" and then never run — the user's first
# contact with the project would be a mystery failure blamed on the mod. Saying
# so up front costs a paragraph; silence costs a compatibility reputation.
if [[ "$(uname -s)" == "Darwin" ]]; then
    echo "  ⚠ macOS：這個發行包目前沒有 macOS 版引擎（只有 Windows 與 Linux x86-64）。"
    echo "    mod 已安裝、可正常遊玩，但結構分析會是關閉狀態。"
    echo "    想在 macOS 跑分析：依 repo 的 sidecar/README.md 自行建置 br-sidecar，"
    echo "    放進上面那個遊戲目錄即可。"
    echo "  macOS: this archive ships no macOS engine (Windows and Linux x86-64 only)."
    echo "    The mod is installed and plays fine; analysis stays off. To get analysis"
    echo "    on a Mac, build br-sidecar from source (see sidecar/README.md in the"
    echo "    repository) and drop it into the game directory above."
elif [[ -f "$HERE/br-sidecar" ]]; then
    if ! cp "$HERE/br-sidecar" "$TARGET/" || ! chmod +x "$TARGET/br-sidecar"; then
        echo "  複製引擎失敗 / copying the engine failed. The mod IS installed and plays;"
        echo "  analysis will stay off until br-sidecar is copied into: $TARGET"
        exit 1
    fi
    echo "  engine -> $TARGET/br-sidecar"
else
    echo "  警告：旁邊沒有 br-sidecar。mod 照樣載入照樣玩，但分析是關的。"
    echo "  WARNING: no br-sidecar next to this script — the mod loads and plays,"
    echo "           analysis is simply off."
fi

echo
if has_forge "$TARGET"; then
    echo "  ✓ 這個實例裡有 Forge。用你平常的啟動器開遊戲就好。"
    echo "    Forge found in this instance. Launch it the way you normally do."
else
    echo "  ⚠ 這個實例裡**沒看到 Forge**。Block Reality 需要 Minecraft 1.20.1 + Forge 47.x。"
    echo "    No Forge in this instance. Block Reality needs Minecraft 1.20.1 + Forge 47.x:"
    echo "    https://files.minecraftforge.net/net/minecraftforge/index.html"
fi
echo
echo '  進遊戲後：創造分頁 "Block Reality"。有問題打 /br status。'
echo '  In game: creative tab "Block Reality". If anything looks off: /br status'
echo
