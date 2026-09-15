#!/usr/bin/env bash
# Compatibility entry point: all release packaging uses the locked SDK.
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
exec python3 "$ROOT/scripts/package_native.py" "$@"
