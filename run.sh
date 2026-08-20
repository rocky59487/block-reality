#!/usr/bin/env bash
# One click: put the engine where the game will find it, then launch the client.
#
# Needs a JDK 17 on PATH (or JAVA_HOME). Gradle comes from the wrapper.
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")"

mkdir -p forge/run

# A locally built engine beats the committed dist binary (#51): after editing the
# sidecar and rebuilding, the run must exercise what was just built, not silently
# regress to the last release's binary.
if [[ -x sidecar/build/br-sidecar ]]; then
    cp sidecar/build/br-sidecar forge/run/
    echo "engine: forge/run/br-sidecar (from sidecar/build)"
elif [[ -x dist/br-sidecar ]]; then
    cp dist/br-sidecar forge/run/
    echo "engine: forge/run/br-sidecar (from dist — no local build found)"
else
    cat <<'MSG'

  No br-sidecar found in dist/ or sidecar/build/.
  The game will still start; structural analysis will be off and will say so.
  Type /br status in game to see every path it looked in.

MSG
fi

cd forge
exec ./gradlew runClient
