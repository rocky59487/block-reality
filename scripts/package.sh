#!/usr/bin/env bash
# Builds everything, runs every gate, and assembles dist/ — the folder you copy
# to another machine.
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

# Everything is assembled into a STAGING directory and only swapped into dist/
# once the whole pipeline has passed. The previous version deleted dist/ first,
# so any mid-pipeline failure left the repository with an empty, half-written
# dist — and dist/ is tracked, so that state was one commit away from shipping.
DIST="$ROOT/dist"
STAGE="$ROOT/dist.stage"
rm -rf "$STAGE"
mkdir -p "$STAGE"
trap 'rm -rf "$STAGE"' EXIT

# ---------------------------------------------------------------- engine (host)
echo "==> building br-sidecar (host)"
cmake -S "$ROOT/sidecar" -B "$ROOT/sidecar/build" \
      -DCMAKE_BUILD_TYPE=Release -DBR_STATIC_RUNTIME=ON \
      -DFRAMECORE_DIR="$FRAMECORE_DIR" >/dev/null
cmake --build "$ROOT/sidecar/build" --parallel >/dev/null
cp "$ROOT/sidecar/build/br-sidecar" "$STAGE/"

# The gate runs before anything is packaged. Shipping an engine that has not passed
# its own acceptance checks would make every number downstream meaningless.
echo "==> verifying engine"
python3 "$ROOT/sidecar/verify.py" "$STAGE/br-sidecar" | tail -1

# ------------------------------------------------------------- engine (Windows)
if command -v x86_64-w64-mingw32-g++ >/dev/null 2>&1; then
    echo "==> cross-building br-sidecar.exe (Windows)"
    cmake -S "$ROOT/sidecar" -B "$ROOT/sidecar/build-win" \
          -DCMAKE_BUILD_TYPE=Release -DBR_STATIC_RUNTIME=ON \
          -DCMAKE_TOOLCHAIN_FILE="$ROOT/sidecar/toolchain-mingw64.cmake" \
          -DFRAMECORE_DIR="$FRAMECORE_DIR" >/dev/null
    cmake --build "$ROOT/sidecar/build-win" --parallel >/dev/null
    cp "$ROOT/sidecar/build-win/br-sidecar.exe" "$STAGE/"
elif [[ "${ALLOW_NO_WINDOWS:-0}" == "1" ]]; then
    echo "==> skipping Windows build (ALLOW_NO_WINDOWS=1, no x86_64-w64-mingw32-g++)"
    echo "    dist/br-sidecar.exe will NOT be in this build"
else
    # Refusing is the whole point. dist/ is TRACKED, and the last two lines of this
    # script are `rm -rf dist` and `mv stage dist` — so on a machine without mingw this
    # used to DELETE the committed, already-verified br-sidecar.exe, and every gate in
    # the repository stayed green afterwards: sha256sum -c regenerates over whatever is
    # there, the release version check never looks at the .exe, and no acceptance suite
    # names it. One `git add` and half the shipped product was gone (PR26_REVIEW A-7).
    echo "no x86_64-w64-mingw32-g++ on PATH, and dist/ already ships a Windows engine." >&2
    echo "Packaging here would delete it. Install the cross compiler:" >&2
    echo "    apt-get install -y g++-mingw-w64-x86-64" >&2
    echo "or, if you really mean to cut a Linux-only build:" >&2
    echo "    ALLOW_NO_WINDOWS=1 scripts/package.sh ..." >&2
    exit 1
fi

# -------------------------------------------------------------- evidence
# The release carries its own verification record: engine commit, binary hash,
# every benchmark against its closed form, cross-platform determinism and timing.
# It runs BEFORE packaging and its exit status gates the release, so a build whose
# numbers moved cannot be shipped with a stale table claiming they did not.
echo "==> generating verification evidence"
EVIDENCE_ARGS=("$STAGE/br-sidecar")
# Wine is not always on PATH even when installed; the distro package puts it under
# /usr/lib/wine. Without it the determinism section is simply absent, never faked.
WINE=$(command -v wine64 || command -v wine || echo /usr/lib/wine/wine64)
if [[ -f "$STAGE/br-sidecar.exe" && -x "$WINE" ]]; then
    cat > "$ROOT/.br-winewrap" <<WRAP
#!/bin/sh
export WINEDEBUG=-all
exec "$WINE" "$STAGE/br-sidecar.exe" "\$@"
WRAP
    chmod +x "$ROOT/.br-winewrap"
    EVIDENCE_ARGS+=(--windows "$ROOT/.br-winewrap")
fi
# Hashed even when wine is absent: the determinism section needs to RUN the Windows
# engine, the identity section only needs to read its bytes.
if [[ -f "$STAGE/br-sidecar.exe" ]]; then
    EVIDENCE_ARGS+=(--windows-binary "$STAGE/br-sidecar.exe")
fi
# Cross-platform determinism WITHOUT wine, and with better evidence than wine gives:
# the .exe run natively on Windows, its replies recorded, compared here byte for byte.
# The file is used only when its header names the binary that was just built, so it can
# never turn into a stale agreement. See docs/RELEASING.md for the three-step order.
REPLIES="$ROOT/evidence/replies-windows.jsonl"
if [[ -f "$STAGE/br-sidecar.exe" && -f "$REPLIES" ]]; then
    want=$(sha256sum "$STAGE/br-sidecar.exe" | cut -d' ' -f1)
    have=$(head -1 "$REPLIES" | python3 -c 'import json,sys; print(json.load(sys.stdin).get("sha256",""))')
    if [[ "$want" == "$have" ]]; then
        EVIDENCE_ARGS+=(--replies "$REPLIES")
    else
        echo "    (evidence/replies-windows.jsonl is for another binary; determinism will be"
        echo "     unchecked in this record — see docs/RELEASING.md)"
    fi
fi
FRAMECORE_DIR="$FRAMECORE_DIR" python3 "$ROOT/scripts/evidence.py" "${EVIDENCE_ARGS[@]}"
rm -f "$ROOT/.br-winewrap"
# The record stays in evidence/ and is NOT copied into dist/. The archive is what a
# player needs in order to play: the mod, the engine, and how to install them. The
# evidence belongs to the repository, where it can be read without downloading a zip.

# ------------------------------------------------------------------- Java tests
# The Java suite runs against the engine THIS run just built — the cross-language
# gates then bind the jar about to be packaged to the binary about to be packaged.
# A release used to be cut with `build -x test` (#46); a package that skips its
# own tests is a capability claim without a gate.
echo "==> running the Java suite against the fresh engine"
(cd "$ROOT/mod" && ./gradlew --no-daemon test -q "-Dbr.sidecar=$STAGE/br-sidecar")

# ------------------------------------------------------------------------- mod
echo "==> building the mod jar"
# Stale jars from earlier versions would be swept up by the copy glob below and
# ship two mods in one zip, each installer picking whichever sorts last. Clear
# first, then ASSERT the glob resolved to exactly one file.
rm -f "$ROOT"/forge/build/libs/blockreality-*.jar
# -PbrEngineDir points at THIS run's engines, not at whatever dist/ happens to hold.
# The jar carries them (D-027) so that installing the mod is dropping one file into
# mods/, which is what CurseForge and Modrinth hand a player. Building the jar from a
# stale dist/ would ship an engine that this run's gates never touched.
(cd "$ROOT/forge" && ./gradlew --no-daemon build -q "-PbrEngineDir=$STAGE")
jars=("$ROOT"/forge/build/libs/blockreality-*.jar)
if [[ ${#jars[@]} -ne 1 || ! -f "${jars[0]}" ]]; then
    echo "expected exactly one mod jar in forge/build/libs, found: ${jars[*]}" >&2
    exit 1
fi
cp "${jars[0]}" "$STAGE/"

cp "$ROOT/scripts/install.sh" "$ROOT/scripts/install.bat" "$STAGE/"
chmod +x "$STAGE/install.sh" "$STAGE/br-sidecar"

# The two READMEs that go in the archive live in scripts/dist-docs/ rather than in
# a heredoc here, so that editing them does not mean editing the packaging script.
# Apache-2.0 4(a)/4(d): whoever receives the archive receives these with it.
cp "$ROOT/LICENSE" "$ROOT/NOTICE" "$STAGE/"
mkdir -p "$STAGE/third_party"
cp "$ROOT"/third_party/*.txt "$STAGE/third_party/"

cp "$ROOT/scripts/dist-docs/START-HERE.txt" "$STAGE/"
cp "$ROOT/scripts/dist-docs/讀我-中文.txt" "$STAGE/"

# Hashes of everything that is about to be shipped, so a download can be checked
# against the archive it claims to be. Generated over the finished stage, and BEFORE the
# bundle gate, which now also asks whether the list covers everything that is there.
echo "==> hashing"
(cd "$STAGE" && find . -type f ! -name SHA256SUMS.txt -printf '%P\n' | sort \
    | xargs sha256sum > SHA256SUMS.txt)

# The jar must carry the engines this run built and gated, they must be the same bytes the
# evidence record verified and the same bytes shipping loose beside them, the licences must
# be in the jar, and SHA256SUMS.txt must account for every file in the archive. Any subset
# of those agreeing is not enough (D-027).
echo "==> checking the bundled engines"
python3 "$ROOT/scripts/check_bundle.py" "$STAGE"

# ------------------------------------------------------------------- swap + zip
# Only now does dist/ change: the pipeline passed end to end.
rm -rf "$DIST"
mv "$STAGE" "$DIST"
trap - EXIT

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
