#!/usr/bin/env bash
# Assembles the LIBRARY-shape distribution: one jar, no executables in it (D-044).
#
#   scripts/package_natives.sh --lib linux-x86_64=/path/to/libbsi_tectonic.so
#   scripts/package_natives.sh --lib linux-x86_64=... --lib windows-x86_64=.../bsi_tectonic.dll
#
# Produces, in dist-natives/ :
#   blockreality-<version>.jar   the Forge mod, with the engine LIBRARY inside it
#   LICENSE NOTICE third_party/  what Apache-2.0 4(a)/4(d) says a recipient gets
#   START-HERE.txt               how to install it, which is: drop the jar in mods/
#   SHA256SUMS.txt               hashes of everything in the archive
#
# WHY A SEPARATE SCRIPT, and not a flag on package.sh. package.sh builds br-sidecar from
# FrameCore sources, runs sidecar/verify.py against it, and drives scripts/evidence.py --
# a harness that speaks the sidecar's protocol and knows nothing about BSI. None of that
# applies to a library, and bolting a second mode onto it would leave one script where
# half the steps are inert depending on an argument. They will merge when the sidecar
# retires (SWAP_PROGRAM phase 3) and the evidence harness has a BSI arm.
#
# WHY dist-natives/ AND NOT dist/. dist/ is TRACKED and currently holds the verified
# sidecar release. package.sh ends in `rm -rf dist`, and doing that from here would delete
# a shipped, gated artefact for a shape that is not yet the released one. This script never
# touches dist/.
#
# HONEST LIMITS, recorded rather than worked around:
#   * There is no evidence record for the library shape. scripts/evidence.py drives the
#     sidecar. The hash chain here is jar == manifest == what the ENGINE SAID ABOUT ITSELF
#     (scripts/stage_natives.py loads each library and asks it), which is two independent
#     legs, not the three the sidecar shape has. Registered in docs/GATES.md under N24-a3.
#   * Windows and macOS libraries are not built by this project yet. A run with only a
#     Linux library produces a jar that tells other platforms so, and that is the intended
#     behaviour, not a silent gap.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
OUT="$ROOT/dist-natives"
LIBS=()
REQUIRE_CLEAN=--require-clean

while [[ $# -gt 0 ]]; do
    case "$1" in
        --lib)        LIBS+=("$2"); shift 2 ;;
        --out)        OUT="$2"; shift 2 ;;
        --allow-dirty) REQUIRE_CLEAN=""; shift ;;
        -h|--help)    sed -n '2,30p' "$0"; exit 0 ;;
        *) echo "unknown argument: $1" >&2; exit 2 ;;
    esac
done

if [[ ${#LIBS[@]} -eq 0 ]]; then
    echo "usage: scripts/package_natives.sh --lib <os>-<arch>=/path/to/library [--lib ...]" >&2
    exit 2
fi

STAGE="$ROOT/.dist-natives.stage"
NATIVES="$ROOT/.natives.stage"
rm -rf "$STAGE" "$NATIVES"
mkdir -p "$STAGE"
trap 'rm -rf "$STAGE" "$NATIVES"' EXIT

# ---------------------------------------------------------------- stage the libraries
# stage_natives.py LOADS each library and asks it bsi.hello. A library that will not open,
# answers with a contract this repository does not pin, or cannot name its own version
# stops the release here rather than in a player's log.
echo "==> staging the engine libraries"
STAGE_ARGS=(--out "$NATIVES")
for spec in "${LIBS[@]}"; do STAGE_ARGS+=(--lib "$spec"); done
[[ -n "$REQUIRE_CLEAN" ]] && STAGE_ARGS+=("$REQUIRE_CLEAN")
python3 "$ROOT/scripts/stage_natives.py" "${STAGE_ARGS[@]}"

# ---------------------------------------------------------------- the Java suite
# Against THIS run's library, so the cross-language leg (N24-b5) binds the jar about to be
# packaged to the bytes about to be packaged. A release cut with the tests skipped is a
# capability claim with no gate behind it (#46).
FIRST_LIB="${LIBS[0]#*=}"
echo "==> running the Java suite against the fresh engine library"
(cd "$ROOT/mod" && ./gradlew --no-daemon test -q "-Dbr.engine=$FIRST_LIB")

# ---------------------------------------------------------------- the jar
echo "==> building the mod jar"
rm -f "$ROOT"/forge/build/libs/blockreality-*.jar
(cd "$ROOT/forge" && ./gradlew --no-daemon build -q -PbrEngineDir=none "-PbrNativesDir=$NATIVES")
jars=("$ROOT"/forge/build/libs/blockreality-*.jar)
if [[ ${#jars[@]} -ne 1 || ! -f "${jars[0]}" ]]; then
    echo "expected exactly one mod jar in forge/build/libs, found: ${jars[*]}" >&2
    exit 1
fi
cp "${jars[0]}" "$STAGE/"

# ---------------------------------------------------------------- what travels with it
cp "$ROOT/LICENSE" "$ROOT/NOTICE" "$STAGE/"
mkdir -p "$STAGE/third_party"
cp "$ROOT"/third_party/*.txt "$STAGE/third_party/"
cp "$ROOT/scripts/dist-docs/START-HERE-engine-library.txt" "$STAGE/START-HERE.txt"
cp "$NATIVES/provenance.json" "$STAGE/engine-provenance.json"

echo "==> hashing"
(cd "$STAGE" && find . -type f ! -name SHA256SUMS.txt -printf '%P\n' | sort \
    | xargs sha256sum > SHA256SUMS.txt)

# The jar must carry the library this run staged, the manifest must describe the bytes
# actually in it, the engine's contract must be the one the mod speaks, the licences must
# be there, and THE JAR MUST CONTAIN NO EXECUTABLES (N24-a1).
echo "==> checking the bundle"
python3 "$ROOT/scripts/check_bundle.py" "$STAGE"

# ...and then check the checker. Seven injections against this very staging directory,
# each of which must turn the gate red. check_bundle.py has been tightened three times,
# every time after something walked past it green (docs/GATES.md 2026-08-23b); a gate
# whose teeth are never tested is a claim about the past.
echo "==> checking that the check still bites"
python3 "$ROOT/scripts/check_bundle_selftest.py" "$STAGE" | tail -3

rm -rf "$OUT"
mv "$STAGE" "$OUT"
trap 'rm -rf "$NATIVES"' EXIT

VERSION=$(basename "$(ls "$OUT"/blockreality-*.jar)" .jar)
VERSION=${VERSION#blockreality-}
ZIP="$ROOT/blockreality-${VERSION}-engine-library.zip"
rm -f "$ZIP"
(cd "$OUT" && zip -q -r "$ZIP" . -x '*.zip')

echo
echo "$(basename "$OUT")/ ready:"
ls -lh "$OUT"
echo
echo "release archive: $ZIP"
