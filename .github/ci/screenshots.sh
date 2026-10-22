#!/usr/bin/env bash
# Runs on the emulator (see .github/workflows/screenshots.yml): installs the debug build,
# makes it the default SMS app with a conversation in it, then screenshots system Settings
# and Twill's screens for each colour style and light/dark, and samples their colours.
set -uo pipefail

APK="$1"
OUT="$2"
PKG=app.twill.debug
UI="python3 .github/ci/ui.py"
mkdir -p "$OUT"

shot() {
    sleep 2
    adb exec-out screencap -p > "$OUT/$1.png"
    echo "$1: $(adb shell dumpsys activity activities | grep -m1 -o 'topResumedActivity=[^ ]* [^ ]* [^ ]*')" >> "$OUT/log.txt"
}
back() { adb shell input keyevent KEYCODE_BACK; sleep 1; }

adb logcat -c
adb install -r -g "$APK" 2>&1 | tee -a "$OUT/log.txt"
adb shell cmd role add-role-holder android.app.role.SMS "$PKG" 0
for permission in READ_SMS SEND_SMS RECEIVE_SMS RECEIVE_MMS READ_CONTACTS READ_PHONE_STATE \
    READ_PHONE_NUMBERS POST_NOTIFICATIONS; do
    adb shell pm grant "$PKG" "android.permission.$permission" 2>/dev/null
done

# A conversation: two incoming texts, and one sent from Twill.
adb emu sms send 5551234 "Hey, are we still on for tonight?"
sleep 3
adb emu sms send 5551234 "I can bring dessert"
sleep 3
adb shell am start -a android.intent.action.SENDTO -d sms:5551234 --es sms_body "Yes! See you at 7"
sleep 6
$UI tapcontains "Send"
sleep 8
adb shell am force-stop "$PKG"

for style in MONOCHROMATIC TONAL_SPOT; do
    $UI palette "$style"
    sleep 10
    for mode in no yes; do
        adb shell cmd uimode night "$mode"
        sleep 6
        tag="$( [ "$style" = MONOCHROMATIC ] && echo other || echo wallpaper )-$( [ "$mode" = no ] && echo light || echo dark )"

        adb shell am start -a android.settings.SETTINGS
        shot "$tag-00-system-settings"
        adb shell am force-stop com.android.settings

        adb shell am force-stop "$PKG"
        adb shell am start -W -n "$PKG/com.android.messaging.ui.MainActivity" >> "$OUT/log.txt" 2>&1
        shot "$tag-01-conversation-list"

        $UI tap "More options"
        shot "$tag-02-menu"
        $UI tap "Settings"
        shot "$tag-03-twill-settings"
        $UI tap "SMS Settings"
        shot "$tag-04-sms-settings"
        back
        $UI tap "Nostr Settings"
        shot "$tag-05-nostr-settings"
        back
        $UI tap "Delta Chat Settings"
        shot "$tag-06-deltachat-settings"
        back
        back

        $UI tapcontains "dessert"
        shot "$tag-07-conversation"
        adb shell am force-stop "$PKG"
    done
done

adb shell settings get secure theme_customization_overlay_packages >> "$OUT/log.txt"
adb logcat -d -b crash > "$OUT/crash.txt"
adb logcat -d | grep -iE "AndroidRuntime|twill|FATAL" | tail -200 > "$OUT/logcat.txt"
python3 .github/ci/ui.py report "$OUT"
