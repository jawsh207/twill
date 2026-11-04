#!/usr/bin/env bash
# Runs on the emulator (see .github/workflows/screenshots.yml): installs the debug build,
# makes it the default SMS app with a conversation in it, then screenshots system Settings
# and Twill's screens for each colour style and light/dark, and samples their colours.
set -uo pipefail

APK="$1"
OUT="$2"
PKG="${3:-app.twill.debug}"
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

# No heads-up notifications over the screenshots.
adb shell settings put global heads_up_notifications_enabled 0

# First run: get through onboarding (SMS warning; role and permissions are already granted).
adb shell am start -W -a android.intent.action.MAIN -c android.intent.category.LAUNCHER -p "$PKG"
sleep 4
for _ in 1 2 3; do
    $UI tap "Next" || break
    sleep 3
done
shot "first-run"

# A conversation: two incoming texts, and one sent from Twill.
adb emu sms send 5551234 "Hey, are we still on for tonight?"
sleep 3
adb emu sms send 5551234 "I can bring dessert"
sleep 3
adb shell am start -a android.intent.action.SENDTO -d sms:5551234
sleep 6
$UI tap "Send message"
adb shell input text "Yes%sSee%syou%sat%s7"
sleep 2
$UI tap "Send Message"
sleep 10
shot "first-run-sent"
adb shell am force-stop "$PKG"
adb shell cmd notification cancel_all 2>/dev/null || adb shell service call notification 1


# Nostr: create a key, then start a chat with a throwaway key and send a message. This runs
# the optimized build's crypto (secp256k1), storage and relay connections.
NPUB=$(python3 .github/ci/nostr_key.py)
# The account signs in with a known key, so a test sender can message it (nostr_dm.py).
KEYS=$(mktemp)
NSEC=$(python3 .github/ci/nostr_dm.py account "$KEYS")
echo "nostr test peer: $NPUB" >> "$OUT/log.txt"
adb shell am start -W -a android.intent.action.MAIN -c android.intent.category.LAUNCHER -p "$PKG" >> "$OUT/log.txt" 2>&1
sleep 3
$UI tap "Settings"
$UI tap "Nostr Settings"
sleep 2
$UI tap "Use a private key" || $UI dump "$OUT/nostr-settings.xml"
sleep 2
shot "nostr-1-key-dialog"
$UI tap "Private key (nsec…)" || $UI dump "$OUT/nostr-key-dialog.xml"
adb shell input text "$NSEC"
sleep 2
$UI tap "Sign in" || true
sleep 5
shot "nostr-2-signed-in"
back
back
sleep 2
$UI longpress "Start chat" || $UI dump "$OUT/nostr-start.xml"
sleep 2
shot "nostr-3-picker"
$UI tap "Nostr chat or group" || $UI dump "$OUT/nostr-picker.xml"
sleep 1
$UI tap "New chat" || $UI dump "$OUT/nostr-new.xml"
sleep 2
$UI tap "Name, name@domain or npub" || $UI dump "$OUT/nostr-field.xml"
adb shell input text "$NPUB"
sleep 1
shot "nostr-4-new-chat"
$UI tap "Start chat" || $UI dump "$OUT/nostr-start-chat.xml"
sleep 8
$UI tap "Nostr message" || $UI dump "$OUT/nostr-chat.xml"
adb shell input text "Hello%sfrom%sthe%soptimized%sbuild"
sleep 1
$UI tap "Send Message" || $UI dump "$OUT/nostr-send.xml"
sleep 15
shot "nostr-5-sent"
back
sleep 2
shot "nostr-6-inbox"
# Suggestions: the start of the key just messaged finds them in the new-chat box.
adb shell am force-stop "$PKG"
adb shell am start -W -a android.intent.action.MAIN -c android.intent.category.LAUNCHER -p "$PKG" >> "$OUT/log.txt" 2>&1
sleep 3
$UI longpress "Start chat" || $UI dump "$OUT/nostr-suggest-start.xml"
sleep 2
$UI tap "Nostr chat or group" || true
sleep 1
$UI tap "New chat" || true
sleep 2
$UI tap "Name, name@domain or npub" || $UI dump "$OUT/nostr-suggest-field.xml"
adb shell input text "${NPUB:0:10}"
sleep 3
shot "nostr-7-suggestions"
$UI dump "$OUT/nostr-suggestions.xml"
back
back
# Notifications: with the inbox open (connected, no chat on screen), receive two DMs from a
# test sender, open the chat's bubble, then look at the notification and reply from it.
# Bubbles: "All conversations can bubble" for Twill, so they appear without a tap first.
adb shell cmd notification set_bubbles "$PKG" 1 >> "$OUT/log.txt" 2>&1 || true
python3 .github/ci/nostr_dm.py send "$KEYS" "Hi, are we still on for lunch?" >> "$OUT/log.txt" 2>&1
sleep 8
python3 .github/ci/nostr_dm.py send "$KEYS" "I can do 12:30 at the usual place" >> "$OUT/log.txt" 2>&1
sleep 10
adb shell input keyevent KEYCODE_HOME
sleep 3
shot "nostr-8a-bubble"
$UI dump "$OUT/nostr-bubble.xml"
# The bubble is a system overlay (not in the view dump): tap where it sits, top left.
# The first tap closes Android's one-time "Chat using bubbles" tip.
adb shell input tap 70 537
sleep 3
adb shell input tap 70 537
sleep 5
shot "nostr-8b-bubble-open"
$UI dump "$OUT/nostr-bubble-open.xml"
back
sleep 2
adb shell cmd statusbar expand-notifications
sleep 2
shot "nostr-8-notification"
$UI dump "$OUT/nostr-notification.xml"
if $UI tap "Reply"; then
    sleep 2
    adb shell input text "Sounds%sgood"
    sleep 1
    shot "nostr-9-reply-typing"
    $UI tap "Send" || $UI tapcontains "Send" || adb shell input keyevent KEYCODE_ENTER
    sleep 10
    shot "nostr-10-replied"
fi
adb shell cmd statusbar collapse
sleep 1
# The reply is in the chat, sent.
$UI tap "Robin Test" || $UI tapcontains "npub1" || $UI dump "$OUT/nostr-chat-after-reply.xml"
sleep 3
shot "nostr-11-chat-after-reply"
adb shell am force-stop "$PKG"

# The bottom bar (Settings option): turn it on now that SMS and Nostr are both on, then look
# at both tabs. It stays on for the rest of the run.
adb shell am start -W -a android.intent.action.MAIN -c android.intent.category.LAUNCHER -p "$PKG" >> "$OUT/log.txt" 2>&1
sleep 2
$UI tap "Settings"
sleep 1
$UI tap "Bottom bar" || $UI dump "$OUT/bottom-bar-settings.xml"
sleep 1
shot "bottom-bar-1-setting"
back
sleep 2
shot "bottom-bar-2-sms"
# Swipe left over the SMS row: no swipe-to-archive any more, so the pager moves to Nostr.
adb shell input swipe 900 450 150 450 250
sleep 2
shot "bottom-bar-3-nostr-swiped"
$UI tap "Start chat" || $UI dump "$OUT/bottom-bar-start.xml"
sleep 2
shot "bottom-bar-4-start-chat"
back
sleep 1
# Back from the Nostr tab returns to the SMS tab instead of leaving the app.
back
sleep 2
shot "bottom-bar-5-back-to-sms"
$UI tap "Nostr"
sleep 2
shot "bottom-bar-6-nostr-tapped"
$UI tap "SMS"
adb shell am force-stop "$PKG"

# Home screen widgets: add "Conversations" and "Conversation" (picking 555-1234) from the
# launcher's widget picker. Each step is screenshotted, and the view tree is saved when a
# step can't find what it taps.
add_widget() { # $1 widget label, $2 file tag, $3 $4 an empty spot on the home screen
    adb shell input keyevent KEYCODE_HOME
    sleep 1
    adb shell input keyevent KEYCODE_HOME
    sleep 2
    adb shell input swipe "$3" "$4" "$3" "$4" 1500
    sleep 2
    $UI tap "Widgets" || $UI dump "$OUT/widget-$2-menu.xml"
    sleep 3
    $UI tapcontains "Search" && sleep 1 && adb shell input text "Twill" && sleep 3
    shot "widget-$2-picker-search"
    $UI tapcontains "Twill" || $UI dump "$OUT/widget-$2-search.xml"
    sleep 3
    shot "widget-$2-picker"
    $UI tap "$1" || $UI dump "$OUT/widget-$2-picker.xml"
    sleep 2
    shot "widget-$2-picked"
    $UI tap "Add" || $UI tapcontains "Add to home" || $UI dump "$OUT/widget-$2-add.xml"
    sleep 5
}
add_widget "Conversations" list 540 1300
shot "widget-list-added"
add_widget "Conversation" conversation 170 1530
shot "widget-conversation-picker"
$UI tapcontains "555-1234" || $UI dump "$OUT/widget-conversation-configure.xml"
sleep 5
shot "widget-conversation-added"
adb shell input keyevent KEYCODE_HOME

for style in MONOCHROMATIC TONAL_SPOT; do
    $UI palette "$style" >> "$OUT/log.txt"
    sleep 10
    for mode in no yes; do
        adb shell cmd uimode night "$mode"
        sleep 6
        tag="$( [ "$style" = MONOCHROMATIC ] && echo other || echo wallpaper )-$( [ "$mode" = no ] && echo light || echo dark )"

        adb shell am start -a android.settings.SETTINGS
        shot "$tag-00-system-settings"
        adb shell am force-stop com.android.settings

        adb shell am force-stop "$PKG"
        adb shell am start -W -a android.intent.action.MAIN -c android.intent.category.LAUNCHER -p "$PKG" >> "$OUT/log.txt" 2>&1
        shot "$tag-01-conversation-list"

        # The inbox search: find the conversation by a word in it, then open the result.
        $UI tap "Search messages"
        sleep 1
        adb shell input text "dessert"
        sleep 3
        shot "$tag-02-search"
        $UI tapcontains "can bring dessert" || $UI dump "$OUT/$tag-search.xml"
        sleep 3
        shot "$tag-02-search-opened"
        adb shell am force-stop "$PKG"
        adb shell am start -W -a android.intent.action.MAIN -c android.intent.category.LAUNCHER -p "$PKG" >> "$OUT/log.txt" 2>&1
        sleep 2
        $UI tap "Settings"
        shot "$tag-03-twill-settings"
        $UI tap "SMS Settings"
        shot "$tag-04-sms-settings"
        $UI tap "Archived"
        shot "$tag-04-archived"
        back
        $UI tap "Blocked contacts"
        shot "$tag-04-blocked"
        back
        back
        $UI tap "Nostr Settings"
        shot "$tag-05-nostr-settings"
        back
        $UI tap "Delta Chat Settings"
        shot "$tag-06-deltachat-settings"
        back
        back

        $UI tapcontains "555-1234"
        shot "$tag-07-conversation"

        # Widgets on the home screen (both pages, wherever the launcher put them), before
        # Twill is force-stopped: Android masks a stopped app's widgets.
        adb shell input keyevent KEYCODE_HOME
        sleep 1
        adb shell input keyevent KEYCODE_HOME
        sleep 3
        shot "$tag-08-widgets"
        adb shell input swipe 900 1200 150 1200 300
        shot "$tag-09-widgets-page-2"
        adb shell input keyevent KEYCODE_HOME
        adb shell am force-stop "$PKG"
    done
done

adb shell settings get secure theme_customization_overlay_packages >> "$OUT/log.txt"
adb logcat -d -b crash > "$OUT/crash.txt"
adb logcat -d | grep -iE "AndroidRuntime|twill|FATAL" | tail -200 > "$OUT/logcat.txt"
python3 .github/ci/ui.py report "$OUT"
