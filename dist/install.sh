#!/usr/bin/env bash
# Copies the mod and the engine into a Minecraft instance.
#
#   ./install.sh ~/.minecraft
#   ./install.sh "~/PrismLauncher/instances/BR-Dev/.minecraft"
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
TARGET="${1:-}"

if [[ -z "$TARGET" ]]; then
    echo "usage: ./install.sh <minecraft instance directory>" >&2
    echo "  the directory that contains mods/, config/ and saves/" >&2
    exit 2
fi
if [[ ! -d "$TARGET" ]]; then
    echo "not a directory: $TARGET" >&2
    exit 2
fi

mkdir -p "$TARGET/mods"

JAR=$(ls "$HERE"/blockreality-*.jar 2>/dev/null | head -1 || true)
if [[ -z "$JAR" ]]; then
    echo "no blockreality-*.jar next to this script" >&2
    exit 1
fi

# Old versions are removed rather than left beside the new one: two copies of the
# same mod in mods/ is a load error, and an unhelpful one.
rm -f "$TARGET"/mods/blockreality-*.jar
cp "$JAR" "$TARGET/mods/"
echo "mod    -> $TARGET/mods/$(basename "$JAR")"

# The engine goes in the game directory, which is where SidecarLocator looks.
for exe in br-sidecar br-sidecar.exe; do
    if [[ -f "$HERE/$exe" ]]; then
        cp "$HERE/$exe" "$TARGET/"
        chmod +x "$TARGET/$exe" 2>/dev/null || true
        echo "engine -> $TARGET/$exe"
    fi
done

echo
echo "Done. Launch Minecraft 1.20.1 with Forge 47.x."
echo "In game, /br status will say whether the engine was found."
