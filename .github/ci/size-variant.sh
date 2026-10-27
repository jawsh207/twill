#!/usr/bin/env bash
# Experiment only: applies one size variant to the working tree before building.
set -euo pipefail
case "$1" in
    baseline) ;;
    no-bundled-sqlite)
        cat >> app/build.gradle.kts <<'KTS'

configurations.configureEach {
    exclude(group = "androidx.sqlite", module = "sqlite-bundled")
    exclude(group = "androidx.sqlite", module = "sqlite-bundled-android")
}
KTS
        ;;
    obfuscate)
        sed -i '/^-dontobfuscate/d' proguard.flags ;;
    optimize)
        sed -i '/^-dontoptimize/d' proguard.flags ;;
    obfuscate-optimize)
        sed -i '/^-dontobfuscate/d; /^-dontoptimize/d' proguard.flags ;;
    narrow-keeps)
        # Upstream keeps every class in com.android.messaging (three package levels) and
        # several androidx packages whole. Keep only what Android instantiates by name.
        sed -i '/^-keep class com.android.messaging.\*/d; /^-keep class androidx\./d; /^-keep class com.android.messaging.ui.contact/d' proguard.flags proguard-release.flags ;;
    *) echo "unknown variant $1"; exit 1 ;;
esac
# Record what R8 was told and what it removed.
mkdir -p size
printf '%s\n' "-printconfiguration $PWD/size/r8-configuration.txt" "-printusage $PWD/size/r8-usage.txt" >> proguard-release.flags
