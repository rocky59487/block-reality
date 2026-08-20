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
#   dist/START-HERE.txt               instructions, English
#   dist/讀我-中文.txt                instructions, Chinese
#   dist/SHA256SUMS.txt               hashes of everything in the archive
#   blockreality-<version>.zip        all of the above, one file
#
# The verification record is NOT in the archive. It stays in evidence/ — the release
# carries what is needed to play, and the evidence stays where it can be read.
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

# -------------------------------------------------------------- evidence
# The release carries its own verification record: engine commit, binary hash,
# every benchmark against its closed form, cross-platform determinism and timing.
# It runs BEFORE packaging and its exit status gates the release, so a build whose
# numbers moved cannot be shipped with a stale table claiming they did not.
echo "==> generating verification evidence"
EVIDENCE_ARGS=("$DIST/br-sidecar")
# Wine is not always on PATH even when installed; the distro package puts it under
# /usr/lib/wine. Without it the determinism section is simply absent, never faked.
WINE=$(command -v wine64 || command -v wine || echo /usr/lib/wine/wine64)
if [[ -f "$DIST/br-sidecar.exe" && -x "$WINE" ]]; then
    cat > "$ROOT/.br-winewrap" <<WRAP
#!/bin/sh
export WINEDEBUG=-all
exec "$WINE" "$DIST/br-sidecar.exe" "\$@"
WRAP
    chmod +x "$ROOT/.br-winewrap"
    EVIDENCE_ARGS+=(--windows "$ROOT/.br-winewrap")
fi
FRAMECORE_DIR="$FRAMECORE_DIR" python3 "$ROOT/scripts/evidence.py" "${EVIDENCE_ARGS[@]}"
rm -f "$ROOT/.br-winewrap"
# The record stays in evidence/ and is NOT copied into dist/. The archive is what a
# player needs in order to play: the mod, the engine, and how to install them. The
# evidence belongs to the repository, where it can be read without downloading a zip.

# ------------------------------------------------------------------------- mod
echo "==> building the mod jar"
(cd "$ROOT/forge" && ./gradlew --no-daemon build -x test -q)
cp "$ROOT"/forge/build/libs/blockreality-*.jar "$DIST/"

cp "$ROOT/scripts/install.sh" "$ROOT/scripts/install.bat" "$DIST/"
chmod +x "$DIST/install.sh" "$DIST/br-sidecar"

# The two READMEs that go in the archive live in scripts/dist-docs/ rather than in
# a heredoc here, so that editing them does not mean editing the packaging script.
cp "$ROOT/scripts/dist-docs/START-HERE.txt" "$DIST/"
cp "$ROOT/scripts/dist-docs/讀我-中文.txt" "$DIST/"

# Hashes of everything that is about to be shipped, so a download can be checked
# against the archive it claims to be. Generated last, over the finished dist/.
echo "==> hashing"
(cd "$DIST" && sha256sum -- * > SHA256SUMS.txt)

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
