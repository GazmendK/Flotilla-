#!/usr/bin/env bash
set -euo pipefail

if [ $# -lt 1 ]; then
  echo "usage: $0 <seed> [test-class-pattern] [ticks]" >&2
  echo "example: $0 77 '*AdversarialTest*' 800" >&2
  exit 2
fi

SEED="$1"
PATTERN="${2:-*RandomizedRaftTest*}"
TICKS="${3:-1500}"

cd "$(dirname "$0")/.."

exec ./gradlew :flotilla-testing:test \
  --tests "$PATTERN" \
  -Dflotilla.sim.seed="$SEED" \
  -Dflotilla.sim.ticks="$TICKS" \
  --rerun-tasks
