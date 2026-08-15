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

cat > "$DIST/START-HERE.txt" <<'TXT'
Block Reality - Demo v0
=======================

Structural analysis for Minecraft, solved by a real finite element engine.
Minecraft 1.20.1 + Forge 47.x.


INSTALL
-------
Windows:  double-click  install.bat
Linux:    ./install.sh

With no arguments it finds your Minecraft instance by itself. If you have several
it lists them and asks you to pick, rather than guessing:

    install.bat "D:\games\my-instance\.minecraft"
    ./install.sh ~/.minecraft


WHAT GETS INSTALLED
-------------------
  blockreality-*.jar   the Forge mod       -> <instance>/mods/
  br-sidecar[.exe]     the physics engine  -> <instance>/

There is no FrameCore.dll. FrameCore is statically linked into br-sidecar, and
br-sidecar is a SEPARATE PROCESS rather than a library the mod loads. That way a
crash in the C++ costs you one analysis instead of the server and your save.

The mod finds the engine automatically: config file, then -Dbr.sidecar, then
BR_SIDECAR, then the game directory, then PATH. Without it the mod still loads and
plays perfectly well - analysis is simply off, and it tells you so.


IN GAME
-------
Creative tab "Block Reality":  Structural Steel,  Stress Glasses.

  1. Put five Structural Steel blocks in a line out from a stone wall.
     The one touching the wall counts as grounded.
  2. Hold the Stress Glasses.
  3. Sneak-right-click the far end to add a 20 kN test load.

The top face of the beam should read BLUE (tension) and the underside RED
(compression), strongest at the wall and fading to nothing at the tip, with a grey
line through the middle where the stress changes sign - the neutral axis.

Right-click the air to change lens: Utilisation -> Stress -> Material.

Build it wrong on purpose:
  - not touching anything    -> "Mechanism, not a structure". That is the correct
                                answer, not a bug: nothing is holding it up, so
                                there are no stresses to report.
  - overload it              -> max D/C goes above 1 and turns red.


IF SOMETHING LOOKS WRONG
------------------------
    /br status     engine state, every path it looked in, and the last result
    /br members    per member: D/C, governing fibre, governing section, peak stress
    /br scan       re-read loaded chunks (for blocks placed by commands or WorldEdit)
    /br resolve    force a re-analysis
    /br reset      restart the engine after it was disabled (OP only)


HONEST NOTE
-----------
The server side has been run in a real Minecraft server and its numbers checked by
hand against closed-form beam theory. The CLIENT RENDERING has never been seen by
anyone - it compiles, and the data behind it is covered by 60 tests, but no one has
looked at the picture yet. If the overlay is invisible or misplaced, that is the
most likely thing to be wrong. /br members will tell you whether the numbers are
there regardless of what is drawn.
TXT

# ------------------------------------------------------------------- release zip
# One file to hand to someone. The scripts inside it are the whole interface.
VERSION=$(basename "$(ls "$DIST"/blockreality-*.jar)" .jar)
VERSION=${VERSION#blockreality-}
ZIP="$ROOT/blockreality-${VERSION}.zip"
rm -f "$ZIP"
(cd "$DIST" && zip -q -r "$ZIP" . -x '*.zip')

echo
echo "dist/ ready:"
ls -lh "$DIST"
echo
echo "release archive: $ZIP"
