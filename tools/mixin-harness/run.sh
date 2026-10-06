#!/usr/bin/env bash
# Before/after check for the mixin fixes in BUGFIX-REPORT.md. All the work is done by Run.java;
# see run.ps1 for Windows. Needs a JDK 11+ as `java` (or JAVA_HOME), git, and Maven Central once.
#
#   tools/mixin-harness/run.sh [baseline-rev]      # default baseline: 96cc13c
set -euo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
JAVA="${JAVA_HOME:+$JAVA_HOME/bin/}java"
exec "$JAVA" "$HERE/Run.java" --harness "$HERE" --baseline "${1:-96cc13c}"
