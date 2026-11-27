#!/usr/bin/env bash
# Run on a disposable API 37 emulator with the test host installed.
set -euo pipefail
: "${ANDROID_SERIAL:?Select the fixture emulator}"
splits=${1:?Path to extracted base, feature, and French APKs}
pkg=com.xlythe.test.extension
component=com.xlythe.test.host/.LoadTest
base=("$splits"/base-master*.apk)
feature=("$splits"/feature-master*.apk)
french=("$splits"/feature-fr*.apk)
abi=("$splits"/feature-x86_64*.apk)
density=("$splits"/feature-mdpi*.apk)
feature_resource=(-e resourcePackage "$pkg.feature" -e verifyFeatureConfig true)

probe() {
    local result
    result=$(adb shell am instrument -w "$@" "$component")
    echo "$result"
    grep -q 'result=PASS' <<< "$result"
}

add_split() {
    local apk session
    session=$(adb shell pm install-create -r -p "$pkg" | sed -n 's/.*\[\([0-9]*\)\].*/\1/p')
    test -n "$session"
    for apk in "$@"; do
        adb push "$apk" /data/local/tmp/ee-fixture-split.apk
        adb shell pm install-write "$session" "$(basename "$apk")" /data/local/tmp/ee-fixture-split.apk
    done
    adb shell pm install-commit "$session" | grep -F Success
}

# Replace any previous fixture installation with just the new base.
adb install --no-streaming -r "${base[0]}"
probe -e expectUnavailable true
add_split "${feature[0]}" "${abi[0]}" "${density[0]}"
probe "${feature_resource[@]}" -e language fr -e expectBaseLanguage true
add_split "${french[0]}"
probe "${feature_resource[@]}" -e language fr
adb shell pm uninstall "$pkg" feature.config.fr | grep -F Success
probe "${feature_resource[@]}" -e language fr -e expectBaseLanguage true
# Package Manager may enforce the installed module's required density/ABI types on partial removal.
removal=$(adb shell pm uninstall "$pkg" feature.config.x86_64 feature.config.mdpi feature) || true
echo "$removal"
case "$removal" in
    *Success*) ;;
    *INSTALL_FAILED_MISSING_SPLIT*)
        probe "${feature_resource[@]}"
        adb install --no-streaming -r "${base[0]}" ;;
    *) exit 1 ;;
esac
probe -e expectUnavailable true
# Complete split session with the base supplied last.
adb install-multiple --no-streaming -r "${french[0]}" "${abi[0]}" "${density[0]}" "${feature[0]}" "${base[0]}"
probe "${feature_resource[@]}" -e language fr
