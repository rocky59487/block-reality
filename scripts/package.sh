#!/usr/bin/env bash
# Single native-library release entry point. See docs/RELEASING.md.
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
exec python3 "$ROOT/scripts/package_native.py" "$@"
