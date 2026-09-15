#!/usr/bin/env bash
# Launch the development client. Native discovery occurs lazily inside the mod.
#
# Needs a JDK 17 on PATH (or JAVA_HOME). Gradle comes from the wrapper.
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")"

mkdir -p forge/run

cat <<'MSG'

  Native engine: set BR_ENGINE to a compatible shared library, or configure enginePath.
  The development jar contains no engine by default. /br status reports the selected source.
  See docs/GAME_RUNTIME.md for placement and migration instructions.

MSG

cd forge
exec ./gradlew runClient
