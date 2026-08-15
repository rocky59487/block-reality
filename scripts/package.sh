#!/usr/bin/env bash
# Builds everything and assembles dist/ — the folder you copy to another machine.
#
#   scripts/package.sh /path/to/architect_simulator/Plugins/FrameSolver/Source/FrameCore
#
# Produces:
#   dist/blockreality-<version>.jar   the Forge mod (api + core + impl in one jar)
#   dist/br-sidecar                   Linux engine, no dependencies but libc
#   dist/br-sidecar.exe               Windows engine, if a mingw cross-compiler is present
#   dist/install.sh / install.bat     copies both into a Minecraft instance
#
# The engine is a SEPARATE PROCESS, not a library the mod loads (DECISIONS D-013).
# There is no FrameCore .dll to ship: FrameCore is statically linked into br-sidecar,
# so a crash in C++ costs you one analysis instead of the server and the save.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
FRAMECORE_DIR="${1:-${FRAMECORE_DIR:-}}"

if [[ -z "$FRAMECORE_DIR" || ! -f "$FRAMECORE_DIR/Public/FrameCore/FrameSolver.h" ]]; then
    echo "usage: scripts/package.sh /path/to/Plugins/FrameSolver/Source/FrameCore" >&2
    exit 2
fi

DIST="$ROOT/dist"
rm -rf "$DIST"
mkdir -p "$DIST"

# ---------------------------------------------------------------- engine (host)
echo "==> building br-sidecar (host)"
cmake -S "$ROOT/sidecar" -B "$ROOT/sidecar/build" \
      -DCMAKE_BUILD_TYPE=Release -DBR_STATIC_RUNTIME=ON \
      -DFRAMECORE_DIR="$FRAMECORE_DIR" >/dev/null
cmake --build "$ROOT/sidecar/build" --parallel >/dev/null
cp "$ROOT/sidecar/build/br-sidecar" "$DIST/"

# The gate runs before anything is packaged. Shipping an engine that has not passed
# its own acceptance checks would make every number downstream meaningless.
echo "==> verifying engine"
python3 "$ROOT/sidecar/verify.py" "$DIST/br-sidecar" | tail -1

# ------------------------------------------------------------- engine (Windows)
if command -v x86_64-w64-mingw32-g++ >/dev/null 2>&1; then
    echo "==> cross-building br-sidecar.exe (Windows)"
    cmake -S "$ROOT/sidecar" -B "$ROOT/sidecar/build-win" \
          -DCMAKE_BUILD_TYPE=Release -DBR_STATIC_RUNTIME=ON \
          -DCMAKE_TOOLCHAIN_FILE="$ROOT/sidecar/toolchain-mingw64.cmake" \
          -DFRAMECORE_DIR="$FRAMECORE_DIR" >/dev/null
    cmake --build "$ROOT/sidecar/build-win" --parallel >/dev/null
    cp "$ROOT/sidecar/build-win/br-sidecar.exe" "$DIST/"
else
    echo "==> skipping Windows build (no x86_64-w64-mingw32-g++)"
    echo "    apt-get install -y g++-mingw-w64-x86-64"
fi

# ------------------------------------------------------------------------- mod
echo "==> building the mod jar"
(cd "$ROOT/forge" && ./gradlew --no-daemon build -x test -q)
cp "$ROOT"/forge/build/libs/blockreality-*.jar "$DIST/"

cp "$ROOT/scripts/install.sh" "$ROOT/scripts/install.bat" "$DIST/"
chmod +x "$DIST/install.sh" "$DIST/br-sidecar"

cat > "$DIST/README.txt" <<'TXT'
Block Reality — Demo v0
=======================

Two files matter:

  blockreality-*.jar   the Forge mod          -> <instance>/mods/
  br-sidecar[.exe]     the structural engine  -> <instance>/     (game directory)

Minecraft 1.20.1 + Forge 47.x.

Install with:   ./install.sh <instance>      or   install.bat <instance>

The engine is a separate process, not a library. The mod finds it automatically:
config -> -Dbr.sidecar -> BR_SIDECAR -> game directory -> PATH.
Without it the mod still loads and plays; analysis is off and it says so.

In game: creative tab "Block Reality" -> Structural Steel, Stress Glasses.
Type /br status if anything looks wrong. It reports where it looked for the engine.
TXT

echo
echo "dist/ ready:"
ls -lh "$DIST"
