#!/usr/bin/env bash
# Runs the unit tests. Pinned to JDK 21 for the same reason as run.sh: Lombok's
# annotation processor does not support JDK 26 (verified: 1.18.34 and 1.18.46 both
# silently generate no accessors there, so compilation fails with "cannot find
# symbol: getX()" across every @Data class). Without this pin, `mvn test` picks up
# whatever JAVA_HOME happens to be set and can fail for reasons unrelated to the code.
set -euo pipefail

export JAVA_HOME="/Users/apple/Library/Java/JavaVirtualMachines/temurin-21.0.11/Contents/Home"
cd "$(dirname "$0")"

exec mvn test "$@"
