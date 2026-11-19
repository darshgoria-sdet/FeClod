#!/usr/bin/env bash
# Runs Flow Capture Studio. Pinned to JDK 21 because Lombok's annotation
# processor does not support JDK 26 (field getters/setters silently fail to
# generate, which breaks compilation). Verified on both the Spring-Boot-managed
# 1.18.34 and the newer 1.18.46 — bumping Lombok is not a way out of this, so
# the pin stays until a Lombok release supports JDK 26. See also test.sh.
set -euo pipefail

export JAVA_HOME="/Users/apple/Library/Java/JavaVirtualMachines/temurin-21.0.11/Contents/Home"
cd "$(dirname "$0")"

exec mvn spring-boot:run
