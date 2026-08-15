#!/usr/bin/env bash
# Block Reality installer.
#
#   ./install.sh                    look for an instance in the usual places
#   ./install.sh ~/.minecraft       or say where it is
#
# With no argument it installs only when it finds EXACTLY ONE instance. Guessing
# between several and silently picking the wrong one is worse than asking.
set -uo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
TARGET="${1:-}"

echo
echo "  Block Reality - Demo v0"
echo "  ======================="
echo

if [[ -z "$TARGET" ]]; then
    CANDIDATES=()
    add() { [[ -d "$1/mods" ]] && CANDIDATES+=("$1"); }

    add "$HOME/.minecraft"
    add "$HOME/.var/app/com.mojang.Minecraft/.minecraft"
    for d in "$HOME"/.local/share/PrismLauncher/instances/*/.minecraft \
             "$HOME"/.local/share/multimc/instances/*/.minecraft \
             "$HOME"/.local/share/ModrinthApp/profiles/* \
             "$HOME"/Library/Application\ Support/minecraft; do
        [[ -e "$d" ]] && add "$d"
    done

    if [[ ${#CANDIDATES[@]} -eq 0 ]]; then
        cat <<'MSG'
  No Minecraft instance found.

  Looked in:
    ~/.minecraft
    ~/.local/share/PrismLauncher/instances/*/.minecraft
    ~/.local/share/multimc/instances/*/.minecraft
    ~/.local/share/ModrinthApp/profiles/*

  Run it with the folder instead - the one containing mods/ and saves/:
    ./install.sh /path/to/instance/.minecraft
MSG
        exit 1
    fi
    if [[ ${#CANDIDATES[@]} -gt 1 ]]; then
        echo "  Found ${#CANDIDATES[@]} instances. Pick one and pass it in:"
        echo
        for c in "${CANDIDATES[@]}"; do echo "    ./install.sh \"$c\""; done
        exit 1
    fi
    TARGET="${CANDIDATES[0]}"
    echo "  Found one instance:"
    echo "    $TARGET"
    echo
fi

if [[ ! -d "$TARGET" ]]; then
    echo "  Not a directory: $TARGET"
    exit 2
fi

mkdir -p "$TARGET/mods"

JAR=$(ls "$HERE"/blockreality-*.jar 2>/dev/null | head -1 || true)
if [[ -z "$JAR" ]]; then
    echo "  No blockreality-*.jar next to this script."
    exit 1
fi

# Two copies of the same mod in mods/ is a load error, so the old one goes first.
rm -f "$TARGET"/mods/blockreality-*.jar
cp "$JAR" "$TARGET/mods/"
echo "  mod    -> $TARGET/mods/$(basename "$JAR")"

# The engine goes in the game directory, which is where the mod looks for it.
if [[ -f "$HERE/br-sidecar" ]]; then
    cp "$HERE/br-sidecar" "$TARGET/"
    chmod +x "$TARGET/br-sidecar"
    echo "  engine -> $TARGET/br-sidecar"
else
    echo "  WARNING: no br-sidecar next to this script."
    echo "           The mod will load and play, but analysis will be off."
fi

echo
echo "  Done. You still need Minecraft 1.20.1 with Forge 47.x in that instance."
echo '  In game: creative tab "Block Reality", and /br status if anything looks wrong.'
