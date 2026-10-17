#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
# Current shared transit models include validated form types; use the real Maven classpath.
sh mvnw -q -Dtest=PersonalCommutePlannerTests test
