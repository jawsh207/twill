#!/usr/bin/env bash
# Runs ./gradlew with the given arguments. If the build fails, the important part of the
# output (Kotlin compile errors, R8 errors, and Gradle's "What went wrong" section) is
# re-emitted as a GitHub error annotation so it can be read through the API without logs.
set -o pipefail
log="$(mktemp)"
./gradlew "$@" 2>&1 | tee "$log"
status=${PIPESTATUS[0]}
if [ "$status" -ne 0 ]; then
  summary="$({
    grep -E '^e: |ERROR:|error:|Missing class|Duplicate class' "$log" | head -n 40
    echo '----'
    sed -n '/What went wrong/,/Try:/p' "$log" | head -n 40
    echo '---- last lines ----'
    grep -v '^\s*$' "$log" | grep -v '^> Task' | tail -n 25
  } | sed -e 's/%/%25/g' | awk 'BEGIN{ORS="%0A"} {print}')"
  echo "::error title=Gradle build failed::${summary}"
fi
exit "$status"
